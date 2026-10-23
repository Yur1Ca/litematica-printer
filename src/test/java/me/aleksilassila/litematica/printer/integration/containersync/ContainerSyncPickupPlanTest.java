package me.aleksilassila.litematica.printer.integration.containersync;

import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.List;
import static me.aleksilassila.litematica.printer.integration.containersync.ContainerSyncPlanTest.*;
import static org.junit.jupiter.api.Assertions.*;

class ContainerSyncPickupPlanTest {
    @BeforeAll static void setup() { bootstrap(); }

    @ParameterizedTest @CsvSource({"64,32,0", "63,32,0", "3,2,0", "2,1,0", "64,32,7", "63,32,7"})
    void halfStackTransferNeedsOnlyTwoAuthoritativeClicks(int source, int moved, int target) {
        var plan = ContainerSyncPlan.create(List.of(stone(target + moved)),
                List.of(stone(target), stone(source)), RULES);
        ContainerSyncPickupSemantics.verify(plan, RULES);
        assertEquals(2, plan.steps().size(), "Taking the rounded-up half and inserting it requires exactly two PICKUPs");
        var end = plan.steps().get(plan.steps().size() - 1);
        assertEquals(source - moved, end.items().get(1).getCount());
        assertEquals(target + moved, end.items().get(0).getCount());
    }

    @Test void everyQuantityPreservesTheOriginalResultWithoutMoreClicks() {
        int[] sources = {1, 2, 3, 4, 7, 16, 31, 32, 33, 63, 64};
        int[] targets = {0, 1, 7, 31};
        for (int source : sources) for (int target : targets) {
            for (int moved = 1; moved <= Math.min(source, 64 - target); moved++) {
                var plan = ContainerSyncPlan.create(List.of(stone(target + moved)),
                        List.of(stone(target), stone(source)), RULES);
                ContainerSyncPickupSemantics.verify(plan, RULES);
                int originalClicks = moved == source ? 2 : moved + 2;
                assertTrue(plan.steps().size() <= originalClicks,
                        "Optimization regressed source=" + source + ", target=" + target + ", moved=" + moved);
                var end = plan.steps().get(plan.steps().size() - 1);
                assertEquals(target + moved, end.items().get(0).getCount());
                assertEquals(source - moved, end.items().get(1).getCount());
            }
        }
    }

    @ParameterizedTest @CsvSource({"64,16,0,16", "64,16,8,24", "31,16,0,16", "63,32,7,39"})
    void everyIntermediateInsertionRespectsTheActualSlotCapacity(int source, int moved, int target, int capacity) {
        var rules = new ContainerSyncPlan.Rules() {
            public boolean movable(int slot) { return true; }
            public int capacity(int slot, ItemStack stack) { return slot == 0 ? capacity : 64; }
        };
        var plan = ContainerSyncPlan.create(List.of(stone(target + moved)),
                List.of(stone(target), stone(source)), rules);
        ContainerSyncPickupSemantics.verify(plan, rules);
        for (var step : plan.steps()) assertTrue(step.items().get(0).getCount() <= capacity);
    }

    @Test void successiveMovesKeepEveryCursorRecoverableAndDoNotMutateInputs() {
        var template = List.of(stone(32), stone(16), stone(8));
        var initial = List.of(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, stone(64));
        var plan = ContainerSyncPlan.create(template, initial, RULES);
        ContainerSyncPickupSemantics.verify(plan, RULES);
        var end = plan.steps().get(plan.steps().size() - 1);
        assertEquals(List.of(32, 16, 8, 8), end.items().stream().map(ItemStack::getCount).toList());
        assertEquals(64, initial.get(3).getCount());
        assertTrue(initial.get(0).isEmpty());
        assertEquals(32, template.get(0).getCount());
    }

    static ContainerSyncPlan halfPlan() {
        // A hand-derived legal plan also exercises transfer recovery before the planner is optimized.
        return new ContainerSyncPlan(List.of(ItemStack.EMPTY, stone(64)), List.of(
                new ContainerSyncPlan.Step(1, 1, 1, List.of(ItemStack.EMPTY, stone(32)), stone(32)),
                new ContainerSyncPlan.Step(0, 0, 1, List.of(stone(32), stone(32)), ItemStack.EMPTY)), null);
    }
}
