//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import me.aleksilassila.litematica.printer.printer.action.ActionPort;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Exercise the real trench callback against the action queue's immediate and deferred completion rules. */
class TrenchFillQueueLifecycleTest {
    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    @Test
    void queuedSuccessUsesTheActualDispatchTickAndSurvivesThatTicksPreprocess() throws Exception {
        try (var f = waitingFixture()) {
            assertTrue(f.execute());
            assertEquals(0, f.sentThisTick());
            assertNotNull(f.completionListener, "A look-delayed fill must observe its eventual queue completion");

            f.level.tick = 106;
            assertEquals(ActionPort.SendResult.SENT, f.completeWaiting(ActionPort.SendResult.SENT));
            assertEquals(106L, f.lastSentTick(), "Do not charge rate history to the earlier queue tick");
            assertEquals(1, f.sentThisTick());
            // TickScheduler advances a queued look before MineHandler.preprocess starts this tick.
            f.beginTick(106);
            assertEquals(1, f.sentThisTick(), "Preprocess must not forget the placement already sent this tick");
            assertFalse(f.canSend(106, 0, 1), "The delayed dispatch still consumes the per-tick budget");
            f.addTarget(146);
            assertFalse(f.canAttempt(146), "The actual dispatch keeps its full world-confirmation window");
            f.addTarget(147);
            assertTrue(f.canAttempt(147));
        }
    }

    @ParameterizedTest
    @EnumSource(value = ActionPort.SendResult.class,
            names = {"STALE_POSITION", "HELD_ITEM_CHANGED", "INTERACTION_REJECTED", "NO_QUEUED_ACTION"})
    void failedQueuedActionsBecomeRetryableWithoutWaitingFortyTicks(ActionPort.SendResult failure) throws Exception {
        try (var f = waitingFixture()) {
            assertTrue(f.execute());
            assertNotNull(f.completionListener);
            f.level.tick = 106;
            f.completeWaiting(failure);
            assertEquals(Long.MIN_VALUE, f.lastSentTick());
            assertEquals(0, f.sentThisTick());
            assertTrue(f.canAttempt(108), "A queue failure was never a server-side placement in flight");
        }
    }

    @Test
    void explicitQueueCancellationAlsoReleasesTheReservedTarget() throws Exception {
        try (var f = waitingFixture()) {
            assertTrue(f.execute());
            assertNotNull(f.completionListener);
            f.level.tick = 106;
            f.actions.cancelQueue();
            assertFalse(f.queuePresent);
            assertTrue(f.canAttempt(108));
            assertEquals(Long.MIN_VALUE, f.lastSentTick());
        }
    }

    @Test
    void aWorldUpdateBeforeDispatchCannotReleaseTheStillQueuedPosition() throws Exception {
        try (var f = waitingFixture()) {
            assertTrue(f.execute());
            f.blockUpdated(true);
            assertFalse(f.canAttempt(101), "An ordinary fluid update is not completion of the queued click");
            assertTrue(f.queuePresent);
            assertNotNull(f.completionListener);
            f.level.tick = 102;
            f.completeWaiting(ActionPort.SendResult.SENT);
            f.blockUpdated(false);
            assertTrue(f.canAttempt(103));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void oldCompletionCannotConsumeANewReservationAfterReset(boolean changeWorld) throws Exception {
        try (var f = waitingFixture()) {
            assertTrue(f.execute());
            var oldCompletion = f.completionListener;
            assertNotNull(oldCompletion);
            f.clearTargets();
            f.actions.cancelQueue();
            if (changeWorld) f.changeWorld(200);
            else f.level.tick = 200;
            f.addTarget(200);
            assertTrue(f.execute());
            assertTrue(f.queuePresent);
            assertEquals(0, f.sentThisTick());

            oldCompletion.accept(ActionPort.SendResult.SENT);
            assertEquals(0, f.sentThisTick(), "A callback from an old scope cannot settle a new target at the same coordinates");
            assertEquals(Long.MIN_VALUE, f.lastSentTick());
            assertFalse(f.canAttempt(200));
            f.completeWaiting(ActionPort.SendResult.SENT);
            assertEquals(1, f.sentThisTick());
            assertEquals(200L, f.lastSentTick());
        }
    }

    @Test
    void anImmediateSuccessIsCountedExactlyOnceEvenThoughCompletionRunsInline() throws Exception {
        try (var f = new TrenchFillIntegrationTest.Fixture()) {
            f.player.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
            assertFalse(f.execute());
            assertEquals(1, f.sentThisTick());
            assertEquals(100L, f.lastSentTick());
            assertFalse(f.queuePresent);
        }
    }

    private static TrenchFillIntegrationTest.Fixture waitingFixture() throws Exception {
        var fixture = new TrenchFillIntegrationTest.Fixture();
        fixture.player.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        fixture.result = ActionPort.SendResult.WAITING_FOR_LOOK;
        return fixture;
    }
}
//#endif
