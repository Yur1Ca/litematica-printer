package me.aleksilassila.litematica.printer.integration.containersync;

import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Independent ordinary PICKUP interpreter; it never reads the planner's predicted result. */
final class ContainerSyncPickupSemantics {
    final List<ItemStack> items;
    ItemStack cursor = ItemStack.EMPTY;
    private final ContainerSyncPlan.Rules rules;

    ContainerSyncPickupSemantics(List<ItemStack> initial, ContainerSyncPlan.Rules rules) {
        this.items = new ArrayList<>();
        for (ItemStack stack : initial) this.items.add(stack.copy());
        this.rules = rules;
    }

    void click(int slot, int button) {
        assertTrue(slot >= 0 && slot < items.size(), "A move must never drop outside the menu");
        assertTrue(button == 0 || button == 1, "Only ordinary left/right PICKUP is supported");
        assertTrue(rules.movable(slot), "A protected slot must not be clicked");
        ItemStack there = items.get(slot);
        if (cursor.isEmpty()) {
            assertFalse(there.isEmpty(), "The planner must not spend a click on an empty source");
            int taken = button == 0 ? there.getCount() : (there.getCount() + 1) / 2;
            cursor = there.copy();
            cursor.setCount(taken);
            there.shrink(taken);
            if (there.isEmpty()) items.set(slot, ItemStack.EMPTY);
            return;
        }
        assertTrue(there.isEmpty() || ContainerSyncPlan.same(there, cursor), "Moves must not swap a foreign stack");
        int room = Math.max(0, Math.min(cursor.getMaxStackSize(), rules.capacity(slot, cursor)) - there.getCount());
        int inserted = Math.min(room, button == 0 ? cursor.getCount() : 1);
        assertTrue(inserted > 0, "The planner must not spend a click on a full destination");
        if (there.isEmpty()) {
            ItemStack placed = cursor.copy();
            placed.setCount(inserted);
            items.set(slot, placed);
        } else there.grow(inserted);
        cursor.shrink(inserted);
        if (cursor.isEmpty()) cursor = ItemStack.EMPTY;
    }

    void assertSnapshot(ContainerSyncPlan.Step step) {
        assertTrue(ContainerSyncPlan.matches(items, step.items()), "Predicted slots differ from real PICKUP semantics");
        assertTrue(ItemStack.matches(cursor, step.cursor()), "Predicted cursor differs from real PICKUP semantics");
    }

    static void verify(ContainerSyncPlan plan, ContainerSyncPlan.Rules rules) {
        assertNull(plan.problem());
        var actual = new ContainerSyncPickupSemantics(plan.initial(), rules);
        int total = total(actual.items, actual.cursor);
        for (var step : plan.steps()) {
            actual.click(step.slot(), step.button());
            actual.assertSnapshot(step);
            assertEquals(total, total(actual.items, actual.cursor), "Every intermediate step must conserve item count");
            if (!actual.cursor.isEmpty()) {
                var recovered = new ContainerSyncPickupSemantics(actual.items, rules);
                recovered.cursor = actual.cursor.copy();
                recovered.click(step.returnSlot(), 0);
                assertTrue(recovered.cursor.isEmpty(), "The entire owned cursor must fit in its recovery origin");
                assertEquals(total, total(recovered.items, recovered.cursor));
            }
        }
        assertTrue(actual.cursor.isEmpty(), "A completed plan must leave no carried stack");
    }

    private static int total(List<ItemStack> items, ItemStack cursor) {
        int total = cursor.getCount();
        for (ItemStack stack : items) total += stack.getCount();
        return total;
    }
}
