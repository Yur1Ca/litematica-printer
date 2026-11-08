package me.aleksilassila.litematica.printer.utils.minecraft;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.aleksilassila.litematica.printer.integration.quickshulker.InventoryRecoveryFence;
//#if MC >= 12105
import net.minecraft.network.HashedStack;
//#endif
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
//#if MC < 12105
//$$ import net.minecraft.world.item.ItemStack;
//#endif

/** Builds non-mutating requests for an authoritative player-inventory snapshot. */
public final class InventorySyncPackets {
    private InventorySyncPackets() { }

    /** Append this marker after the full snapshot request, on the same connection. */
    public static SyncFence prepareFence(Object connection) {
        return connection == null ? null : new SyncFence(connection, InventoryRecoveryFence.prepare(connection));
    }

    public static final class SyncFence {
        private final Object connection;
        private final InventoryRecoveryFence.Ticket ticket;

        private SyncFence(Object connection, InventoryRecoveryFence.Ticket ticket) {
            this.connection = connection;
            this.ticket = ticket;
        }

        public ServerboundClientCommandPacket packet() { return this.ticket.packet(); }
        public boolean isForConnection(Object connection) { return this.connection == connection; }

        // The USE and sync request themselves invalidate the recovery ticket's
        // input-generation check. Its ordered reply ordinal still proves that
        // the preceding full snapshot was applied before this marker reply.
        public boolean complete() { return this.ticket.complete(); }
    }

    /** Returns null while another menu or a carried stack owns the player's inventory. */
    public static ServerboundContainerClickPacket createPlayerInventoryFullSync(Player player) {
        if (player == null || player.inventoryMenu == null
                || player.containerMenu != player.inventoryMenu
                || !player.inventoryMenu.getCarried().isEmpty()) return null;
        // Slot -1 PICKUP is a vanilla no-op; revision -1 forces a full authoritative reply.
        // An empty changed-slot map makes no claims about earlier predicted inventory changes.
        ContainerInput input = ContainerInput.PICKUP;
        //#if MC >= 12105
        return new ServerboundContainerClickPacket(player.inventoryMenu.containerId, -1,
                (short) -1, (byte) 0, input, new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY);
        //#else
        //$$ return new ServerboundContainerClickPacket(player.inventoryMenu.containerId, -1,
        //$$         -1, 0, input, ItemStack.EMPTY, new Int2ObjectOpenHashMap<>());
        //#endif
    }
}
