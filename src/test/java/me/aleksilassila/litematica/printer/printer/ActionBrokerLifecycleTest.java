package me.aleksilassila.litematica.printer.printer;

import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.core.runtime.*;
import me.aleksilassila.litematica.printer.printer.action.ActionBroker;
import me.aleksilassila.litematica.printer.printer.action.ActionPort;
import net.minecraft.core.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ActionBrokerLifecycleTest {
    private static final class Queue extends ActionManager {
        int cancellations;
        int sends;
        @Override public boolean queueClick(BlockPos pos, Direction side, Vec3 hit, boolean shift,
                                             int count, Item[] items, ActionSource source) { return true; }
        @Override public void cancelQueue() { cancellations++; }
        @Override public SendResult sendQueue(net.minecraft.client.player.LocalPlayer player) {
            sends++;
            return SendResult.WAITING_FOR_LOOK;
        }
    }

    @Test void stopCancelsOnlyOwnedUnsentQueueAndLeavesDrainingInventoryLease() {
        var epoch = new AtomicReference<>(RuntimeEpoch.INITIAL);
        var lifecycle = new FeatureLifecycle();
        lifecycle.observe("fill", true);
        lifecycle.observe("print", true);
        var queue = new Queue();
        var broker = new ActionBroker(epoch::get, () -> lifecycle, queue);
        assertTrue(broker.queueClick(BlockPos.ZERO, Direction.DOWN, Vec3.ZERO, false, 1, null, ActionPort.ActionSource.FILL));
        broker.cancelOwnerQueue("print");
        assertEquals(0, queue.cancellations);
        lifecycle.observe("fill", false);
        broker.cancelOwnerQueue("fill");
        assertEquals(1, queue.cancellations);
        assertTrue(broker.tryAcquire("inventory_drain", EnumSet.of(ResourceLease.INVENTORY, ResourceLease.MAIN_HAND), 0));
        for (int tick = 0; tick < 300; tick++) {
            broker.cancelOwnerQueue("fill");
            assertTrue(broker.isResourceHeld(ResourceLease.INVENTORY));
        }
        broker.releaseOwner("inventory_drain"); // full inventory acknowledgement completes recovery
        assertFalse(broker.isResourceHeld(ResourceLease.INVENTORY));
    }

    @Test void oldGenerationOrConnectionCannotDispatchWaitingLook() {
        var epoch = new AtomicReference<>(RuntimeEpoch.INITIAL);
        var lifecycle = new FeatureLifecycle();
        lifecycle.observe("mine", true);
        var queue = new Queue();
        var broker = new ActionBroker(epoch::get, () -> lifecycle, queue);
        assertTrue(broker.queueClick(BlockPos.ZERO, Direction.DOWN, Vec3.ZERO, false, 1, null, ActionPort.ActionSource.MINE));
        lifecycle.observe("mine", false);
        lifecycle.observe("mine", true);
        assertEquals(ActionPort.SendResult.NO_QUEUED_ACTION, broker.sendQueue(null));
        assertEquals(0, queue.sends);
        assertTrue(broker.queueClick(BlockPos.ZERO, Direction.DOWN, Vec3.ZERO, false, 1, null, ActionPort.ActionSource.MINE));
        epoch.set(epoch.get().next());
        assertEquals(ActionPort.SendResult.NO_QUEUED_ACTION, broker.sendQueue(null));
        assertEquals(0, queue.sends);
    }
}
