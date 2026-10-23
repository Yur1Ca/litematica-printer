package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.util.ArrayList;
import java.util.List;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.integration.containersync.ContainerSyncPlan;
import me.aleksilassila.litematica.printer.integration.containersync.ContainerSyncTransfer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

/** Keeps an orderly return's menu and lease alive until all earlier clicks have been synchronized. */
final class OrderedStorageDrain {
    private AbstractContainerMenu ownedMenu;
    private AbstractContainerMenu requestedMenu;
    private Object connection;
    private int revision;
    private List<Integer> slots = List.of();
    private ContainerSyncTransfer transfer;
    private boolean needsAttention;
    private boolean restored;

    boolean active() { return ownedMenu != null; }
    boolean restored() { return restored; }

    void begin(Minecraft client, AbstractContainerMenu menu, int returnSlot, List<Integer> destinations) {
        this.ownedMenu = menu;
        this.connection = client.getConnection();
        this.requestedMenu = null;
        this.needsAttention = false;
        this.restored = false;
        var selected = new ArrayList<Integer>();
        selected.add(returnSlot);
        int remaining = menu.slots.get(returnSlot).getItem().getCount();
        for (int destination : destinations) {
            if (remaining <= 0) break;
            selected.add(destination);
            remaining -= OrderedStorageStacks.availableCapacity(menu.slots.get(destination), menu.slots.get(returnSlot).getItem());
        }
        this.slots = List.copyOf(selected);
        var initial = snapshot(menu);
        var state = new ArrayList<>(ContainerSyncPlan.copy(initial));
        var cursor = state.get(0).copy();
        state.set(0, ItemStack.EMPTY);
        var steps = new ArrayList<ContainerSyncPlan.Step>();
        var source = menu.slots.get(returnSlot);
        int hotbar = source.getContainerSlot();
        boolean swap = slots.size() == 2 && client.player != null
                && source.container == client.player.getInventory() && hotbar >= 0 && hotbar < 9
                && source.mayPickup(client.player) && state.get(1).isEmpty()
                && menu.slots.get(slots.get(1)).mayPlace(cursor)
                && menu.slots.get(slots.get(1)).getMaxStackSize(cursor) >= cursor.getCount();
        if (swap) {
            // The complete hotbar stack fits the first preferred empty slot. A single vanilla
            // number-key swap has the same final state and never puts the stack on the cursor.
            state.set(1, cursor.copy());
            steps.add(new ContainerSyncPlan.Step(1, hotbar, 0, ContainerSyncPlan.copy(state), ItemStack.EMPTY));
        } else {
            steps.add(new ContainerSyncPlan.Step(0, 0, 0, ContainerSyncPlan.copy(state), cursor.copy()));
            for (int i = 1; i < slots.size(); i++) {
                var target = state.get(i);
                int moved = Math.min(cursor.getCount(), OrderedStorageStacks.availableCapacity(menu.slots.get(slots.get(i)), cursor));
                var after = cursor.copy();
                after.setCount(target.getCount() + moved);
                state.set(i, after);
                cursor.shrink(moved);
                steps.add(new ContainerSyncPlan.Step(i, 0, 0, ContainerSyncPlan.copy(state), cursor.copy()));
            }
        }
        this.transfer = new ContainerSyncTransfer(new ContainerSyncTransfer.Port() {
            public Object context() {
                return client.player != null && client.getConnection() == connection && client.player.containerMenu == menu ? menu : null;
            }
            public boolean acquire() { return true; } // The adapter owns all inventory resources throughout this transaction.
            public void release() { } // Release only after closing/synchronizing the menu.
            public void click(int slot, int button) {
                requestedMenu = menu;
                revision = menu.getStateId();
                QuickShulkerDiagnostics.log("orderly-return click menu=" + menu.containerId + " slot=" + slots.get(slot));
                QuickShulkerClicks.sendWithFullSync(menu, slots.get(slot), button,
                        swap ? ContainerInput.SWAP : ContainerInput.PICKUP);
            }
            public void refresh() {
                requestedMenu = menu;
                revision = menu.getStateId();
                QuickShulkerClicks.requestFullSync(menu);
            }
            public boolean unchanged(List<ItemStack> items, ItemStack cursor) {
                return ContainerSyncPlan.matches(items, snapshot(menu)) && ItemStack.matches(cursor, menu.getCarried());
            }
        }, new ContainerSyncPlan(initial, List.copyOf(steps), null));
        tick(client);
    }

    void cancel() { if (transfer != null) transfer.cancel(); }

    private List<ItemStack> snapshot(AbstractContainerMenu menu) {
        return slots.stream().map(slot -> menu.slots.get(slot).getItem().copy()).toList();
    }

    void tick(Minecraft client) {
        if (!active()) return;
        if (client.player == null || client.getConnection() != connection) { discard(); return; }
        var current = client.player.containerMenu;
        if (transfer != null && current == ownedMenu) {
            transfer.tick(System.nanoTime() / 1_000_000L, Configs.Core.WORK_SWITCH.getBooleanValue()
                    && Configs.Placement.QUICK_SHULKER.getBooleanValue()
                    && Configs.Placement.STORE_ORDERLY.getBooleanValue());
            if (transfer.finished()) {
                restored = transfer.completed();
                if (current.getCarried().isEmpty()) {
                    discard();
                    client.player.closeContainer();
                }
            }
            return;
        }
        transfer = null;
        if (requestedMenu != current || needsAttention && current.getCarried().isEmpty()) {
            // After a forced/manual close, synchronize the replacement menu without closing it.
            requestedMenu = current;
            revision = current.getStateId();
            needsAttention = false;
            QuickShulkerDiagnostics.log("orderly-drain request menu=" + current.containerId);
            QuickShulkerClicks.requestFullSync(current);
        }
    }

    boolean observe(Minecraft client, int id, int receivedRevision, List<ItemStack> items, ItemStack cursor) {
        if (!active() || client.player == null || client.getConnection() != connection
                || requestedMenu == null || requestedMenu != client.player.containerMenu
                || id != requestedMenu.containerId || items.size() != requestedMenu.slots.size()
                || !ContainerSyncTransfer.newerRevision(receivedRevision, revision)) return false;
        if (transfer != null && requestedMenu == ownedMenu) {
            // Ignore unrelated inventory changes (for example picked-up piston drops), while every
            // source/destination and the cursor still need exact authoritative confirmation.
            transfer.observe(ownedMenu, slots.stream().map(slot -> items.get(slot)).toList(), cursor);
            // Content is observed only after vanilla has applied the entire packet. Advance at
            // most one click (or close) now, retaining the same cancellation and snapshot checks.
            tick(client);
            return true;
        }
        if (cursor.isEmpty()) {
            QuickShulkerDiagnostics.log("orderly-drain replacement confirmed menu=" + id);
            discard();
        } else {
            if (!needsAttention) QuickShulkerDiagnostics.log("orderly-drain cursor needs manual attention menu=" + id);
            needsAttention = true;
        }
        return true;
    }

    void discard() {
        ownedMenu = null;
        requestedMenu = null;
        connection = null;
        transfer = null;
        slots = List.of();
        needsAttention = false;
    }
}
