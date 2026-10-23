package me.aleksilassila.litematica.printer.integration.inventory;

import java.util.List;
import me.aleksilassila.litematica.printer.integration.containersync.ContainerSyncPlan;
import me.aleksilassila.litematica.printer.integration.containersync.ContainerSyncTransfer;
import me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerClicks;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

/** A single server-confirmed take, or a cursor-safe two-click return. The provider owns the leases. */
final class ChestTrackerTransfer {
    private final Minecraft client;
    private final AbstractContainerMenu menu;
    private final Object connection, level;
    private AbstractContainerMenu requestedMenu;
    private int revision;
    private ContainerSyncTransfer returning;
    private List<Integer> slots = List.of();
    private boolean cancelled, finished, completed, attention, refresh, manualPending;

    ChestTrackerTransfer(Minecraft client, AbstractContainerMenu menu) {
        this.client = client;
        this.menu = menu;
        this.connection = client.getConnection();
        this.level = client.level;
    }

    boolean sameWorld() { return client.player != null && client.getConnection() == connection && client.level == level; }
    boolean finished() { return finished; }
    boolean completed() { return completed && !cancelled; }
    boolean needsAttention() { return attention || returning != null && returning.needsAttention(); }
    void cancel() { cancelled = true; if (returning != null) returning.cancel(); }
    void synchronize() { requestSnapshot(client.player.containerMenu); }

    void take(int slot) {
        requestedMenu = menu;
        revision = menu.getStateId();
        QuickShulkerClicks.sendWithFullSync(menu, slot, 0, ContainerInput.QUICK_MOVE);
    }

    void returnStack(int from, int to) {
        slots = List.of(from, to);
        var initial = snapshot();
        var stack = initial.get(0).copy();
        var picked = List.of(ItemStack.EMPTY, ItemStack.EMPTY);
        var placed = List.of(ItemStack.EMPTY, stack.copy());
        var plan = new ContainerSyncPlan(initial, List.of(
                new ContainerSyncPlan.Step(0, 0, 0, picked, stack),
                new ContainerSyncPlan.Step(1, 0, 0, placed, ItemStack.EMPTY)), null);
        returning = new ContainerSyncTransfer(new ContainerSyncTransfer.Port() {
            public Object context() { return sameWorld() && client.player.containerMenu == menu ? menu : null; }
            public boolean acquire() { return true; }
            public void release() { } // The provider closes/drains before releasing ownership.
            public void click(int slot, int button) {
                requestedMenu = menu;
                revision = menu.getStateId();
                QuickShulkerClicks.sendWithFullSync(menu, slots.get(slot), button, ContainerInput.PICKUP);
            }
            public void refresh() { requestSnapshot(menu); }
            public boolean unchanged(List<ItemStack> items, ItemStack cursor) {
                return ContainerSyncPlan.matches(items, snapshot()) && ItemStack.matches(cursor, menu.getCarried());
            }
        }, plan);
        tick();
    }

    private List<ItemStack> snapshot() { return slots.stream().map(slot -> menu.slots.get(slot).getItem().copy()).toList(); }

    void tick() {
        if (finished) return;
        if (!sameWorld()) { finished = true; return; }
        var current = client.player.containerMenu;
        if (current != menu) {
            // A forced close invalidates the old plan, but not the outstanding server mutation.
            cancel();
            returning = null;
            if (requestedMenu != current || refresh || attention && current.getCarried().isEmpty()) requestSnapshot(current);
            return;
        }
        if (refresh || attention && current.getCarried().isEmpty()) requestSnapshot(current);
        if (returning != null) {
            // Its first attention refresh must be consumed before a manual mutation is allowed.
            // Once in attention, this wrapper owns refreshes so they cannot queue ahead of recovery.
            if (returning.needsAttention()) return;
            returning.tick(System.nanoTime() / 1_000_000L, !cancelled);
            if (returning.finished() && current.getCarried().isEmpty()) {
                completed = returning.completed();
                finished = true;
            }
        }
    }

    void observe(int id, int receivedRevision, List<ItemStack> items, ItemStack cursor) {
        if (finished || !sameWorld() || requestedMenu == null || requestedMenu != client.player.containerMenu
                || requestedMenu.containerId != id || items.size() != requestedMenu.slots.size()
                || !ContainerSyncTransfer.newerRevision(receivedRevision, revision)) return;
        revision = receivedRevision;
        manualPending = false;
        if (returning != null && requestedMenu == menu) {
            returning.observe(menu, slots.stream().map(items::get).toList(), cursor);
            if (returning.needsAttention()) {
                if (returning.finished() && cursor.isEmpty()) finished = true;
                else attention = true;
            }
            tick();
        } else if (cursor.isEmpty()) {
            completed = requestedMenu == menu;
            finished = true;
        } else {
            cancel();
            attention = true;
        }
    }

    /** Allow exactly one recovery click, then require a new full snapshot before releasing the lease. */
    boolean guardManualInput() {
        cancel();
        if (!attention || manualPending) return true;
        attention = false;
        refresh = true;
        manualPending = true;
        return false;
    }

    private void requestSnapshot(AbstractContainerMenu current) {
        requestedMenu = current;
        revision = current.getStateId();
        attention = false;
        refresh = false;
        QuickShulkerClicks.requestFullSync(current);
    }
}
