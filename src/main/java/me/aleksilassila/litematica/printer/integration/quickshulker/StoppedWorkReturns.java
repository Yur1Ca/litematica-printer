package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** Only stacks actually borrowed in this client session are eligible after WORK is turned off. */
final class StoppedWorkReturns {
    record Scope(LocalPlayer player, ClientLevel level, ClientPacketListener connection) {
        Scope(Minecraft client) { this(client.player, client.level, client.getConnection()); }
        boolean current(Minecraft client) {
            return player != null && player == client.player && level != null && level == client.level
                    && connection != null && connection == client.getConnection();
        }
    }
    private record Remainder(int slot, int count) {}
    private final Map<OrderedStorageEntry, Remainder> remainders = new IdentityHashMap<>();
    private Scope borrowedIn, stoppedIn;
    private boolean awaitingWithdrawal;
    private OrderedStorageEntry movingHotbarStack;

    void noteWithdrawal(Minecraft client, OrderedStorageEntry entry) {
        if (client.player != null && client.level != null && client.getConnection() != null
                && (borrowedIn == null || !borrowedIn.current(client))) borrowedIn = new Scope(client);
        if (borrowedIn != null && borrowedIn.current(client)) entry.borrowedIn = borrowedIn;
        if (stoppedIn != null && stoppedIn.current(client)) freeze(entry);
    }

    boolean begin(Minecraft client, List<OrderedStorageEntry> entries, boolean pendingWithdrawal, int movingHotbarSlot) {
        if (stoppedIn != null) {
            if (stoppedIn.current(client) && stoppedIn.player().isAlive()) return true;
            disable(entries);
            return false;
        }
        if (client.player == null || client.level == null || client.getConnection() == null
                || !client.player.isAlive() || entries.isEmpty() && !pendingWithdrawal) return false;
        entries.removeIf(entry -> entry.borrowedIn == null || !entry.borrowedIn.current(client));
        if (entries.isEmpty() && !pendingWithdrawal) return false;
        stoppedIn = new Scope(client);
        awaitingWithdrawal = pendingWithdrawal;
        for (var entry : entries) {
            freeze(entry);
            if (pendingWithdrawal && movingHotbarSlot >= 0 && movingHotbarSlot < 9
                    && entry.playerInventorySlot == movingHotbarSlot) movingHotbarStack = entry;
        }
        return true;
    }

    private void freeze(OrderedStorageEntry entry) {
        int slot = entry.playerInventorySlot;
        ItemStack stack = stoppedIn.player().getInventory().getItem(slot);
        int count = OrderedStorageStacks.matches(entry.itemStack, stack) ? stack.getCount() : 0;
        // An already merged stack includes personal stock. A later decrease cannot prove ownership.
        remainders.put(entry, new Remainder(slot, count <= entry.borrowedCount ? count : -1));
    }

    boolean active(Minecraft client, List<OrderedStorageEntry> entries, boolean pendingRestore,
                   OrderedStorageEntry inFlight) {
        if (stoppedIn == null) return false;
        if (!stoppedIn.current(client) || !stoppedIn.player().isAlive()) {
            entries.clear();
            end();
            return false;
        }
        // Only the outstanding return or confirmed hotbar-clear operation can legitimately vacate a frozen slot.
        entries.removeIf(entry -> entry != inFlight && entry != movingHotbarStack && !accepts(entry, stoppedIn.player()));
        if (!awaitingWithdrawal && entries.isEmpty() && !pendingRestore) end();
        return stoppedIn != null;
    }

    boolean accepts(OrderedStorageEntry entry, LocalPlayer player) {
        Remainder remaining = remainders.get(entry);
        if (remaining == null || remaining.slot() != entry.playerInventorySlot) return false;
        var stack = player.getInventory().getItem(remaining.slot());
        if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, entry.itemStack)) return false;
        int count = stack.getCount();
        if (count > remaining.count()) return false;
        if (count < remaining.count()) remainders.put(entry, new Remainder(remaining.slot(), count));
        return true;
    }

    int sourceSlot(OrderedStorageEntry entry, LocalPlayer player, AbstractContainerMenu menu) {
        if (!accepts(entry, player)) return -1;
        return menu.findSlot(player.getInventory(), entry.playerInventorySlot).orElse(-1);
    }

    static int originalBoxSlot(OrderedStorageEntry entry, LocalPlayer player) {
        if (entry == null || player == null || entry.origin == null
                || entry.origin.inventoryMenuSlot != entry.originalBoxSlot
                || entry.attemptedShulkerMenuSlots.contains(entry.originalBoxSlot)) return -1;
        return entry.origin.matchesCarriedBox(player) ? entry.originalBoxSlot : -1;
    }

    static boolean manualInputIdle(Minecraft client) {
        //#if MC > 260100
        //$$ if (client.gui.screen() != null) return false;
        //#else
        if (client.screen != null) return false;
        //#endif
        var using = client.player.getUseItem();
        return (using == null || using.isEmpty()) && client.player.containerMenu.getCarried().isEmpty();
    }

    void finishWithdrawal() { awaitingWithdrawal = false; movingHotbarStack = null; }
    void moved(OrderedStorageEntry entry, int destination, LocalPlayer player) {
        var previous = remainders.get(entry);
        if (entry != movingHotbarStack || previous == null || previous.count() < 0
                || destination < 0 || destination >= 36) return;
        // The server has confirmed that this exact stack moved while the stop fence was settling.
        int limit = previous.count() > 0 ? previous.count() : entry.borrowedCount;
        remainders.put(entry, new Remainder(destination,
                Math.min(limit, player.getInventory().getItem(destination).getCount())));
    }
    void disable(List<OrderedStorageEntry> entries) { if (stoppedIn != null) entries.clear(); end(); }
    void end() { stoppedIn = null; finishWithdrawal(); remainders.clear(); }
    void reset() { end(); borrowedIn = null; }
}
