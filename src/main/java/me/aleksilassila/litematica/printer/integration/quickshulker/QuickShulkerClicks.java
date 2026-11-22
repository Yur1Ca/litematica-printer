package me.aleksilassila.litematica.printer.integration.quickshulker;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.HashedStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

/** Sends a click without predicting it locally, so changed slots are returned by the server. */
public final class QuickShulkerClicks {
    private QuickShulkerClicks() { }

    public static void send(AbstractContainerMenu menu, int slot, int button, ContainerInput type) {
        send(menu, menu.getStateId(), slot, button, type);
    }

    public static void requestFullSync(AbstractContainerMenu menu) {
        // Slot -1 PICKUP is a vanilla no-op (unlike -999, which drops the cursor).
        // Revisions are 0..32767. A mismatch forces a full authoritative snapshot.
        send(menu, -1, -1, 0, ContainerInput.PICKUP);
    }

    /** One click and one authoritative full reply, avoiding a second in-flight snapshot request. */
    public static void sendWithFullSync(AbstractContainerMenu menu, int slot, int button, ContainerInput type) {
        send(menu, -1, slot, button, type);
    }

    /** Report the simulated result of an ordinary PICKUP without mutating the local menu. */
    public static void sendPlannedPickup(AbstractContainerMenu menu, int slot, int button,
                                         ItemStack slotAfter, ItemStack cursorAfter) {
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null) return;
        var hashes = connection.decoratedHashOpsGenenerator();
        var changed = new Int2ObjectOpenHashMap<HashedStack>(1);
        changed.put(slot, HashedStack.create(slotAfter, hashes));
        connection.send(new ServerboundContainerClickPacket(menu.containerId, menu.getStateId(),
                (short) slot, (byte) button, ContainerInput.PICKUP, changed, HashedStack.create(cursorAfter, hashes)));
    }

    private static void send(AbstractContainerMenu menu, int stateId, int slot, int button, ContainerInput type) {
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null) return;
        connection.send(new ServerboundContainerClickPacket(menu.containerId, stateId,
                (short) slot, (byte) button, type, new Int2ObjectOpenHashMap<>(),
                HashedStack.create(menu.getCarried(), connection.decoratedHashOpsGenenerator())));
    }
}
