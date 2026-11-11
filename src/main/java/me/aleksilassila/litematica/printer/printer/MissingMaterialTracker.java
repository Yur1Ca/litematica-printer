package me.aleksilassila.litematica.printer.printer;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.utils.minecraft.PlayerUtils;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeComponent;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Tracks material requirements that have actually blocked an action.
 *
 * <p>The scanner never writes a second material index for this HUD. Print/fill
 * executors report a requirement as soon as the inventory switch cannot supply
 * it. A successful retrieval removes the entry on the next tick.</p>
 */
public final class MissingMaterialTracker implements RuntimeComponent {
    private static final long STALE_AFTER_TICKS = 100L;

    private final Map<Item, TrackedRequirement> requirements = new LinkedHashMap<>();
    private List<Entry> snapshot = List.of();
    private long lastTick = Long.MIN_VALUE;

    public MissingMaterialTracker() {
    }

    public static MissingMaterialTracker getRuntime() {
        return RuntimeAccess.get().missingMaterials();
    }

    public void tick(@Nullable LocalPlayer player, long currentTick) {
        if (!Configs.Core.WORK_SWITCH.getBooleanValue()
                || !Configs.Core.MISSING_MATERIAL_HUD.getBooleanValue()
                || player == null) {
            clear();
            return;
        }
        if (this.lastTick != Long.MIN_VALUE && currentTick < this.lastTick) {
            clear();
        }
        this.lastTick = currentTick;
        if (this.requirements.isEmpty()) {
            return;
        }

        boolean changed = this.requirements.values().removeIf(requirement ->
                currentTick - requirement.lastSeenTick > STALE_AFTER_TICKS
                        || isAvailable(player, requirement)
        );
        if (changed) {
            rebuildSnapshot();
        }
    }

    public void recordMissing(
            Item[] acceptedItems,
            @Nullable Predicate<ItemStack> stackPredicate,
            @Nullable ItemStack preferredStack,
            long currentTick
    ) {
        if (!Configs.Core.MISSING_MATERIAL_HUD.getBooleanValue()) {
            return;
        }
        ItemStack iconStack = createIconStack(acceptedItems, preferredStack);
        if (iconStack.isEmpty() || iconStack.is(Items.AIR)) {
            return;
        }

        Item key = iconStack.getItem();
        TrackedRequirement existing = this.requirements.get(key);
        Item[] normalizedItems = normalizeItems(acceptedItems);
        if (existing != null) {
            existing.acceptedItems = normalizedItems;
            existing.stackPredicate = stackPredicate;
            existing.lastSeenTick = currentTick;
            return;
        }

        this.requirements.put(key, new TrackedRequirement(
                iconStack,
                iconStack.getHoverName(),
                normalizedItems,
                stackPredicate,
                currentTick
        ));
        rebuildSnapshot();
    }

    public void resolve(Item[] acceptedItems, @Nullable Predicate<ItemStack> stackPredicate) {
        if (this.requirements.isEmpty()) {
            return;
        }
        Item[] normalizedItems = normalizeItems(acceptedItems);
        boolean changed = this.requirements.values().removeIf(requirement ->
                overlaps(requirement.acceptedItems, normalizedItems)
                        || stackPredicate != null && requirement.stackPredicate == stackPredicate
        );
        if (changed) {
            rebuildSnapshot();
        }
    }

    public List<Entry> snapshot() {
        return this.snapshot;
    }

    public void clear() {
        this.lastTick = Long.MIN_VALUE;
        if (!this.requirements.isEmpty()) {
            this.requirements.clear();
            this.snapshot = List.of();
        }
    }

    @Override public void onEpochChanged(RuntimeEvent.EpochChanged event) { this.clear(); }

    private void rebuildSnapshot() {
        List<Entry> entries = new ArrayList<>(this.requirements.size());
        for (TrackedRequirement requirement : this.requirements.values()) {
            entries.add(new Entry(requirement.iconStack.copy(), requirement.displayName));
        }
        this.snapshot = List.copyOf(entries);
    }

    private static boolean isAvailable(LocalPlayer player, TrackedRequirement requirement) {
        if (PlayerUtils.getAbilities(player).instabuild) {
            return true;
        }
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (requirement.stackPredicate != null) {
                if (requirement.stackPredicate.test(stack)) {
                    return true;
                }
            } else {
                for (Item acceptedItem : requirement.acceptedItems) {
                    if (stack.is(acceptedItem)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static ItemStack createIconStack(Item[] acceptedItems, @Nullable ItemStack preferredStack) {
        if (preferredStack != null && !preferredStack.isEmpty() && !preferredStack.is(Items.AIR)) {
            ItemStack copy = preferredStack.copy();
            copy.setCount(1);
            return copy;
        }
        if (acceptedItems != null) {
            for (Item item : acceptedItems) {
                if (item != null && item != Items.AIR) {
                    return item.getDefaultInstance();
                }
            }
        }
        return ItemStack.EMPTY;
    }

    private static Item[] normalizeItems(@Nullable Item[] items) {
        if (items == null || items.length == 0) {
            return new Item[0];
        }
        return Arrays.stream(items)
                .filter(item -> item != null && item != Items.AIR)
                .distinct()
                .toArray(Item[]::new);
    }

    private static boolean overlaps(Item[] left, Item[] right) {
        for (Item leftItem : left) {
            for (Item rightItem : right) {
                if (leftItem == rightItem) {
                    return true;
                }
            }
        }
        return false;
    }

    public record Entry(ItemStack iconStack, Component displayName) {
    }

    private static final class TrackedRequirement {
        private final ItemStack iconStack;
        private final Component displayName;
        private Item[] acceptedItems;
        @Nullable
        private Predicate<ItemStack> stackPredicate;
        private long lastSeenTick;

        private TrackedRequirement(
                ItemStack iconStack,
                Component displayName,
                Item[] acceptedItems,
                @Nullable Predicate<ItemStack> stackPredicate,
                long lastSeenTick
        ) {
            this.iconStack = iconStack;
            this.displayName = displayName;
            this.acceptedItems = acceptedItems;
            this.stackPredicate = stackPredicate;
            this.lastSeenTick = lastSeenTick;
        }
    }
}
