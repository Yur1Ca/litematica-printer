package me.aleksilassila.litematica.printer.integration.containersync;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/** Run explicitly with old/new production classpaths; never part of ordinary test execution. */
public final class ContainerSyncPlanBenchmark {
    private static final java.io.PrintStream OUTPUT = System.out;
    private static volatile long consumed;
    private static final ContainerSyncPlan.Rules RULES = new ContainerSyncPlan.Rules() {
        public boolean movable(int slot) { return true; }
        public int capacity(int slot, ItemStack stack) { return stack.getMaxStackSize(); }
    };

    private record Scenario(String name, List<ItemStack> template, List<ItemStack> initial,
                            List<ItemStack> expected) { }

    public static void main(String[] args) {
        ContainerSyncPlanTest.bootstrap();
        int warmup = args.length > 0 ? Integer.parseInt(args[0]) : 1000;
        int iterations = args.length > 1 ? Integer.parseInt(args[1]) : 3000;
        if (warmup < 0 || iterations <= 0) throw new IllegalArgumentException("Expected nonnegative warmup and positive iterations");
        var scenarios = List.of(single("half64", 64, 32), single("odd63", 63, 32),
                single("full64", 64, 64), single("partial1", 64, 1), single("nearFull63", 64, 63), mixed());
        var bean = ManagementFactory.getThreadMXBean();
        com.sun.management.ThreadMXBean allocations = bean instanceof com.sun.management.ThreadMXBean available
                && available.isThreadAllocatedMemorySupported() ? available : null;
        if (allocations != null && !allocations.isThreadAllocatedMemoryEnabled()) allocations.setThreadAllocatedMemoryEnabled(true);
        OUTPUT.println("scenario,iterations,ns_per_plan,bytes_per_plan,steps,modeled_200ms_ack_ms,checksum");
        for (var scenario : scenarios) {
            var sample = create(scenario);
            validate(scenario, sample);
            for (int i = 0; i < warmup; i++) consumed += create(scenario).steps().size();
            long allocatedBefore = allocations == null ? 0 : allocations.getCurrentThreadAllocatedBytes();
            long start = System.nanoTime();
            long checksum = 0;
            for (int i = 0; i < iterations; i++) {
                var plan = create(scenario);
                checksum += plan.steps().size();
                checksum += plan.steps().get(plan.steps().size() - 1).items().get(0).getCount();
            }
            long elapsed = System.nanoTime() - start;
            long allocated = allocations == null ? -1 : allocations.getCurrentThreadAllocatedBytes() - allocatedBefore;
            consumed = checksum;
            validate(scenario, create(scenario));
            OUTPUT.printf(java.util.Locale.ROOT, "%s,%d,%.1f,%.1f,%d,%d,%d%n", scenario.name(), iterations,
                    (double) elapsed / iterations, allocated < 0 ? -1.0 : (double) allocated / iterations,
                    sample.steps().size(), 200L * sample.steps().size(), checksum);
        }
    }

    private static ContainerSyncPlan create(Scenario scenario) {
        return ContainerSyncPlan.create(scenario.template(), scenario.initial(), RULES);
    }

    private static void validate(Scenario scenario, ContainerSyncPlan plan) {
        if (plan.problem() != null || plan.steps().isEmpty()) throw new AssertionError(scenario.name() + ": no plan");
        var end = plan.steps().get(plan.steps().size() - 1);
        if (!end.cursor().isEmpty() || !ContainerSyncPlan.matches(scenario.expected(), end.items())) {
            throw new AssertionError(scenario.name() + ": final inventory differs from hand-derived result");
        }
        ContainerSyncPickupSemantics.verify(plan, RULES);
    }

    private static Scenario single(String name, int source, int count) {
        var template = empty(27);
        template.set(0, new ItemStack(Items.STONE, count));
        var initial = empty(63);
        initial.set(27, new ItemStack(Items.STONE, source));
        var expected = empty(63);
        expected.set(0, new ItemStack(Items.STONE, count));
        if (source > count) expected.set(27, new ItemStack(Items.STONE, source - count));
        return new Scenario(name, template, initial, expected);
    }

    private static Scenario mixed() {
        var template = empty(27);
        var initial = empty(63);
        var expected = empty(63);
        Item[] types = {Items.STONE, Items.DIRT, Items.GLASS};
        int[] counts = {32, 16, 8};
        for (int item = 0; item < types.length; item++) {
            initial.set(27 + item, new ItemStack(types[item], 64));
            expected.set(27 + item, new ItemStack(types[item], 8));
            for (int group = 0; group < counts.length; group++) {
                int slot = group * types.length + item;
                template.set(slot, new ItemStack(types[item], counts[group]));
                expected.set(slot, new ItemStack(types[item], counts[group]));
            }
        }
        return new Scenario("mixed", template, initial, expected);
    }

    private static ArrayList<ItemStack> empty(int size) {
        var result = new ArrayList<ItemStack>(size);
        for (int slot = 0; slot < size; slot++) result.add(ItemStack.EMPTY);
        return result;
    }
}
