package me.aleksilassila.litematica.printer.handler;

import fi.dy.masa.malilib.util.InventoryUtils;
import me.aleksilassila.litematica.printer.config.Configs;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeComponent;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Tracks material gains once per client tick without treating normal consumption as a rescan event. */
public final class InventoryAvailabilityTracker implements RuntimeComponent {
    private final Map<Item, Integer> previousCounts = new IdentityHashMap<>();
    private final Map<Item, Integer> currentCounts = new IdentityHashMap<>();
    /**
     * Items currently reachable in the player's inventory, including shulker box contents.
     * Only kept up to date while {@link Configs.Core#RENDER_ONLY_HOLDING_ITEMS} is enabled, since
     * scanning shulker box contents on every tick is otherwise unnecessary work.
     */
    private final Set<Item> availableItems = new HashSet<>();
    private boolean initialized;
    private long gainRevision;

    public InventoryAvailabilityTracker() {
    }

    public void tick(LocalPlayer player) {
        if (player == null) {
            this.reset();
            return;
        }
        this.currentCounts.clear();
        boolean trackAvailability = Configs.Core.RENDER_ONLY_HOLDING_ITEMS.getBooleanValue();
        if (trackAvailability) {
            this.availableItems.clear();
        }
        int size = player.getInventory().getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) {
                this.currentCounts.merge(stack.getItem(), stack.getCount(), Integer::sum);
                if (trackAvailability) {
                    this.availableItems.add(stack.getItem());
                    if (isShulkerBox(stack)) {
                        this.collectShulkerContents(stack);
                    }
                }
            }
        }
        if (this.initialized) {
            for (Map.Entry<Item, Integer> entry : this.currentCounts.entrySet()) {
                if (entry.getValue() > this.previousCounts.getOrDefault(entry.getKey(), 0)) {
                    this.gainRevision++;
                    break;
                }
            }
        } else {
            this.initialized = true;
        }
        this.previousCounts.clear();
        this.previousCounts.putAll(this.currentCounts);
    }

    private static boolean isShulkerBox(ItemStack stack) {
        return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().contains("shulker_box");
    }

    /** Adds the items stored inside a shulker box stack to {@link #availableItems}. */
    private void collectShulkerContents(ItemStack stack) {
        try {
            for (ItemStack stored : InventoryUtils.getStoredItems(stack, -1)) {
                if (stored != null && !stored.isEmpty()) {
                    this.availableItems.add(stored.getItem());
                }
            }
        } catch (Exception ignored) {
            // Contents unreadable for this stack - nothing to add.
        }
    }

    public long gainRevision() {
        return this.gainRevision;
    }

    /**
     * Whether the given item is currently reachable in the player's inventory (including shulker boxes).
     * Only accurate while {@link Configs.Core#RENDER_ONLY_HOLDING_ITEMS} is enabled; returns an empty
     * view otherwise.
     */
    public boolean isAvailable(Item item) {
        return this.availableItems.contains(item);
    }

    public Set<Item> availableItemsView() {
        return Collections.unmodifiableSet(this.availableItems);
    }

    public void reset() {
        this.previousCounts.clear();
        this.currentCounts.clear();
        this.availableItems.clear();
        this.initialized = false;
        this.gainRevision++;
    }

    @Override public void onEpochChanged(RuntimeEvent.EpochChanged event) { this.reset(); }
}
