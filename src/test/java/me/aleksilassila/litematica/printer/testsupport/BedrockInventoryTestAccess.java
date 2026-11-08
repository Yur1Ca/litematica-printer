package me.aleksilassila.litematica.printer.testsupport;

import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacer;
import me.aleksilassila.litematica.printer.utils.minecraft.InventorySyncPackets;

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
}
