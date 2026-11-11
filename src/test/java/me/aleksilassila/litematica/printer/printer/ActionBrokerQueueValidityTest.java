package me.aleksilassila.litematica.printer.printer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.core.runtime.FeatureLifecycle;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.printer.action.ActionBroker;
import me.aleksilassila.litematica.printer.printer.action.ActionPort;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Keep validity checks at the broker boundary, including retries made before feature preprocess. */
class ActionBrokerQueueValidityTest {
    private static final class Queue extends ActionManager {
        int sends;
        SendResult result = SendResult.WAITING_FOR_LOOK;
        Consumer<SendResult> completion;

        @Override public boolean queueClick(BlockPos pos, Direction side, Vec3 hit, boolean shift,
                                             int count, Item[] items, ActionSource source) { return true; }

        @Override public boolean setQueueCompletionListener(Consumer<SendResult> listener) {
            this.completion = listener;
            return true;
        }

        @Override public void cancelQueue() { this.complete(SendResult.NO_QUEUED_ACTION); }

        @Override public SendResult sendQueue(LocalPlayer player) {
            this.sends++;
            if (!this.result.isWaiting()) this.complete(this.result);
            return this.result;
        }

        private void complete(SendResult result) {
            Consumer<SendResult> listener = this.completion;
            this.completion = null;
            if (listener != null) listener.accept(result);
        }
    }

    private static final class Fixture {
        final AtomicReference<RuntimeEpoch> epoch = new AtomicReference<>(RuntimeEpoch.INITIAL);
        final FeatureLifecycle lifecycle = new FeatureLifecycle();
        final Queue queue = new Queue();
        final ActionBroker broker = new ActionBroker(this.epoch::get, () -> this.lifecycle, this.queue);
        final List<ActionPort.SendResult> completions = new ArrayList<>();

        Fixture() { this.lifecycle.observe("mine", true); }

        void queue() {
            assertTrue(this.broker.queueClick(BlockPos.ZERO, Direction.DOWN, Vec3.ZERO,
                    false, 1, null, ActionPort.ActionSource.MINE));
            assertTrue(this.broker.setQueueCompletionListener(this.completions::add));
        }

        void assertInteractionLeases(boolean held) {
            assertEquals(held, this.broker.isResourceHeld(ResourceLease.LOOK));
            assertEquals(held, this.broker.isResourceHeld(ResourceLease.MAIN_HAND));
            assertEquals(held, this.broker.isResourceHeld(ResourceLease.INTERACTION));
        }
    }

    @Test void invalidatedWaitingLookCompletesWithoutDispatchAndReleasesItsLeases() {
        var f = new Fixture();
        var valid = new AtomicBoolean(true);
        f.queue();
        assertTrue(f.broker.setQueueValidity(valid::get));
        assertEquals(ActionPort.SendResult.WAITING_FOR_LOOK, f.broker.sendQueue(null));
        f.assertInteractionLeases(true);

        valid.set(false);
        assertEquals(ActionPort.SendResult.NO_QUEUED_ACTION, f.broker.sendQueue(null));
        assertEquals(1, f.queue.sends, "A scheduler retry must validate before entering the click executor");
        assertEquals(List.of(ActionPort.SendResult.NO_QUEUED_ACTION), f.completions);
        f.assertInteractionLeases(false);

        f.queue();
        f.queue.result = ActionManager.SendResult.SENT;
        assertEquals(ActionPort.SendResult.SENT, f.broker.sendQueue(null), "Cancellation must clear the old invalid predicate");
    }

    @Test void validWaitingLookCanCompleteAndDoesNotConstrainTheNextAction() {
        var f = new Fixture();
        var valid = new AtomicBoolean(true);
        f.queue();
        assertTrue(f.broker.setQueueValidity(valid::get));
        assertEquals(ActionPort.SendResult.WAITING_FOR_LOOK, f.broker.sendQueue(null));
        assertTrue(f.completions.isEmpty());

        f.queue.result = ActionManager.SendResult.SENT;
        assertEquals(ActionPort.SendResult.SENT, f.broker.sendQueue(null));
        assertEquals(List.of(ActionPort.SendResult.SENT), f.completions);
        f.assertInteractionLeases(false);

        valid.set(false);
        f.queue();
        assertEquals(ActionPort.SendResult.SENT, f.broker.sendQueue(null), "Completion must clear the previous predicate");
        assertEquals(3, f.queue.sends);
    }

    @Test void queuesWithoutValidityKeepTheirWaitingAndCompletionBehavior() {
        var f = new Fixture();
        f.queue();
        assertEquals(ActionPort.SendResult.WAITING_FOR_LOOK, f.broker.sendQueue(null));
        f.assertInteractionLeases(true);
        f.queue.result = ActionManager.SendResult.HELD_ITEM_CHANGED;
        assertEquals(ActionPort.SendResult.HELD_ITEM_CHANGED, f.broker.sendQueue(null));
        assertEquals(List.of(ActionPort.SendResult.HELD_ITEM_CHANGED), f.completions);
        f.assertInteractionLeases(false);
    }

    @Test void epochCancellationClearsValidityBeforeTheNextQueue() {
        var f = new Fixture();
        f.queue();
        assertTrue(f.broker.setQueueValidity(() -> false));
        f.epoch.set(f.epoch.get().next());
        assertEquals(ActionPort.SendResult.NO_QUEUED_ACTION, f.broker.sendQueue(null));
        assertEquals(0, f.queue.sends);
        assertEquals(List.of(ActionPort.SendResult.NO_QUEUED_ACTION), f.completions);
        f.assertInteractionLeases(false);

        f.queue();
        f.queue.result = ActionManager.SendResult.SENT;
        assertEquals(ActionPort.SendResult.SENT, f.broker.sendQueue(null));
    }

    @Test void validityCannotBeInstalledWithoutAnOwnedQueue() {
        var f = new Fixture();
        assertFalse(f.broker.setQueueValidity(() -> false));
        f.queue();
        f.queue.result = ActionManager.SendResult.SENT;
        assertEquals(ActionPort.SendResult.SENT, f.broker.sendQueue(null));
    }
}
