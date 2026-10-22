package me.aleksilassila.litematica.printer.printer;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.core.runtime.*;
import me.aleksilassila.litematica.printer.printer.action.ActionBroker;
import me.aleksilassila.litematica.printer.printer.action.ActionPort;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Characterizes admission/cancellation, with only the terminal Minecraft interaction replaced. */
class FeatureBoundaryBaselineTest {
    private static final List<ActionPort.ActionSource> SOURCES = List.of(
            ActionPort.ActionSource.PRINT, ActionPort.ActionSource.FILL,
            ActionPort.ActionSource.FLUID, ActionPort.ActionSource.MINE);

    private static final class Terminal extends ActionManager {
        final List<String> trace = new ArrayList<>();
        @Override public boolean queueClick(BlockPos pos, Direction side, Vec3 hit, boolean shift,
                                            int count, Item[] items, ActionSource source) {
            trace.add("queue:" + source + ":" + pos.getX() + ":" + side.name() + ":" + count);
            return true;
        }
        @Override public SendResult sendQueue(net.minecraft.client.player.LocalPlayer player) {
            trace.add("wait");
            return SendResult.WAITING_FOR_LOOK;
        }
        @Override public void cancelQueue() { trace.add("cancel"); }
    }

    @ParameterizedTest
    @ValueSource(ints = {0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15})
    void everyEnableCombinationPreservesOwnerAdmissionAndMutualExclusion(int mask) {
        var lifecycle = new FeatureLifecycle();
        var terminal = new Terminal();
        var broker = new ActionBroker(() -> RuntimeEpoch.INITIAL, () -> lifecycle, terminal);
        for (int i = 0; i < SOURCES.size(); i++) lifecycle.observe(owner(SOURCES.get(i)), (mask & 1 << i) != 0);
        for (int i = 0; i < SOURCES.size(); i++) {
            var source = SOURCES.get(i);
            boolean enabled = (mask & 1 << i) != 0;
            terminal.trace.clear();
            assertEquals(enabled, enqueue(broker, source));
            if (!enabled) { assertTrue(terminal.trace.isEmpty()); continue; }
            for (var other : SOURCES) {
                if (other == source) continue;
                broker.cancelOwnerQueue(owner(other));
                assertFalse(enqueue(broker, other), "One click must retain exclusive hand/look ownership");
            }
            assertEquals(ActionPort.SendResult.WAITING_FOR_LOOK, broker.sendQueue(null));
            broker.cancelOwnerQueue(owner(source));
            assertEquals(List.of("queue:" + source + ":3:UP:2", "wait", "cancel"), terminal.trace);
            assertFalse(broker.isResourceHeld(ResourceLease.MAIN_HAND));
        }
    }

    @Test void delayedLookSurvivesOtherFeatureTogglesButCannotCrossItsOwnGenerationOrEpoch() {
        var epoch = new AtomicReference<>(RuntimeEpoch.INITIAL);
        var lifecycle = new FeatureLifecycle();
        for (var source : SOURCES) lifecycle.observe(owner(source), true);
        var terminal = new Terminal();
        var broker = new ActionBroker(epoch::get, () -> lifecycle, terminal);
        assertTrue(enqueue(broker, ActionPort.ActionSource.MINE));
        for (int tick = 0; tick < 60; tick++) {
            lifecycle.observe("fill", tick % 2 == 0);
            broker.cancelOwnerQueue("fill");
            assertEquals(ActionPort.SendResult.WAITING_FOR_LOOK, broker.sendQueue(null));
            assertTrue(broker.isResourceHeldByOther(ResourceLease.MAIN_HAND, "fill"));
        }
        lifecycle.observe("mine", false);
        lifecycle.observe("mine", true);
        assertEquals(ActionPort.SendResult.NO_QUEUED_ACTION, broker.sendQueue(null));
        assertEquals(62, terminal.trace.size()); // queue + 60 waits + cancellation
        assertTrue(enqueue(broker, ActionPort.ActionSource.MINE));
        epoch.set(epoch.get().next());
        assertEquals(ActionPort.SendResult.NO_QUEUED_ACTION, broker.sendQueue(null));
        assertEquals("cancel", terminal.trace.get(63));
    }

    @Test void disablingEveryProducerDoesNotReleaseAContainerDrainOrItsManualExclusion() {
        var lifecycle = new FeatureLifecycle();
        var broker = new ActionBroker(() -> RuntimeEpoch.INITIAL, () -> lifecycle, new Terminal());
        assertTrue(broker.tryAcquire("container_sync",
                EnumSet.of(ResourceLease.INVENTORY, ResourceLease.CONTAINER, ResourceLease.MAIN_HAND), 0));
        for (int tick = 0; tick < 300; tick++) {
            for (var source : SOURCES) {
                lifecycle.observe(owner(source), false);
                broker.cancelOwnerQueue(owner(source));
                assertFalse(enqueue(broker, source));
            }
            assertFalse(broker.tryAcquire("pick_block", EnumSet.of(ResourceLease.MAIN_HAND), 0));
            assertTrue(broker.isResourceHeld(ResourceLease.INVENTORY));
        }
        broker.releaseOwner("container_sync");
        assertTrue(broker.tryAcquire("pick_block", EnumSet.of(ResourceLease.MAIN_HAND), 0));
    }

    private static boolean enqueue(ActionBroker broker, ActionPort.ActionSource source) {
        return broker.queueClick(new BlockPos(3, 64, -1), Direction.UP, Vec3.ZERO, false, 2, null, source);
    }
    private static String owner(ActionPort.ActionSource source) { return source.name().toLowerCase(java.util.Locale.ROOT); }
}
