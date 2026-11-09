package me.aleksilassila.litematica.printer.testsupport;

import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacer;
import me.aleksilassila.litematica.printer.integration.quickshulker.InventoryRecoveryFence;
import me.aleksilassila.litematica.printer.utils.minecraft.InventorySyncPackets;
import net.minecraft.client.Minecraft;

import static org.junit.jupiter.api.Assertions.*;

/** Constructs an actual pending response fence without exposing test hooks in production. */
public final class BedrockInventoryTestAccess {
    private BedrockInventoryTestAccess() { }

    public static void recordFence(Object placer, InventorySyncPackets.SyncFence fence) {
        try {
            var field = BedrockPlacer.class.getDeclaredField("inventory");
            field.setAccessible(true);
            Object inventory = field.get(placer);
            var record = inventory.getClass().getDeclaredMethod("record", InventorySyncPackets.SyncFence.class);
            record.setAccessible(true);
            record.invoke(inventory, fence);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot construct the bedrock inventory response boundary", failure);
        }
    }

    /** Arrange a ready inventory through the same emitted snapshot and ordered reply as production. */
    public static void synchronizeInventory(Object placer, Minecraft client) {
        try {
            Object inventory = inventory(placer);
            var settling = inventory.getClass().getDeclaredMethod("isSettling");
            settling.setAccessible(true);
            assertFalse((boolean) settling.invoke(inventory), "Setup cannot consume an existing test's pending fence");
            var synchronize = inventory.getClass().getDeclaredMethod("synchronize", Runnable.class);
            synchronize.setAccessible(true);
            synchronize.invoke(inventory, (Object) null);
            assertTrue((boolean) settling.invoke(inventory), "Ready setup must first emit a real snapshot and reply marker");
            acknowledgeInventory(placer, client);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot prepare the current bedrock inventory", failure);
        }
    }

    public static void acknowledgeInventory(Object placer, Minecraft client) {
        try {
            Object inventory = inventory(placer);
            var settling = inventory.getClass().getDeclaredMethod("isSettling");
            settling.setAccessible(true);
            assertTrue((boolean) settling.invoke(inventory), "There must be an emitted snapshot to acknowledge");
            var menu = client.player.inventoryMenu;
            var authoritative = menu.slots.stream().map(slot -> slot.getItem().copy()).toList();
            menu.initializeContents(menu.getStateId() + 1, authoritative, menu.getCarried().copy());
            InventoryRecoveryFence.received(client.getConnection().getConnection());
            assertFalse((boolean) settling.invoke(inventory), "Only this connection's actual reply releases the snapshot");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot acknowledge the emitted bedrock inventory snapshot", failure);
        }
    }

    /** Acknowledge only the sequence actually emitted by the native equipment preparation. */
    public static boolean acknowledgeSelectedTool(Object placer, Minecraft client) {
        try {
            var prepare = BedrockPlacer.class.getDeclaredMethod("prepareCriticalToolTick");
            prepare.setAccessible(true);
            if ((boolean) prepare.invoke(placer)) return true;
            Object inventory = inventory(placer);
            var syncField = inventory.getClass().getDeclaredField("toolSync");
            syncField.setAccessible(true);
            Object sync = syncField.get(inventory);
            var sequenceField = sync.getClass().getDeclaredField("pendingSequence");
            sequenceField.setAccessible(true);
            int actualSequence = sequenceField.getInt(sync);
            if (actualSequence < 0) return false; // An empty MAIN hand has no equipment operation to acknowledge.
            var acknowledge = BedrockPlacer.class.getDeclaredMethod("acknowledgeToolTick", Object.class, int.class);
            acknowledge.setAccessible(true);
            acknowledge.invoke(placer, client.getConnection().getConnection(), actualSequence);
            assertTrue((boolean) prepare.invoke(placer));
            return true;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot acknowledge the emitted bedrock equipment marker", failure);
        }
    }

    private static Object inventory(Object placer) throws ReflectiveOperationException {
        var field = BedrockPlacer.class.getDeclaredField("inventory");
        field.setAccessible(true);
        return field.get(placer);
    }
}
