//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Locale;
import java.util.function.Predicate;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Explicit measurement of the real selector; not part of normal test execution. */
public final class MaterialSelectionBenchmark {
    private static final java.io.PrintStream OUTPUT = System.out;
    private static volatile int observed;

    public static void main(String[] args) throws Exception {
        InventoryRegressionTest.bootstrap();
        var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        for (String scenario : new String[]{"reserve-blocked", "held-surplus", "unlimited"}) {
            try (var fixture = new MaterialSelectionFixture()) {
                for (int slot = 0; slot < 36; slot++) {
                    fixture.player.inventory.setItem(slot, new ItemStack(Items.STONE));
                }
                int reserve = scenario.equals("reserve-blocked") ? 36 : scenario.equals("held-surplus") ? 35 : -1;
                Predicate<ItemStack> predicate = stack -> stack.is(Items.STONE);
                var fallback = new ItemStack(Items.STONE);
                int batch = 2000;
                for (int sample = -20; sample < 31; sample++) {
                    long allocated = bean.getThreadAllocatedBytes(thread);
                    long start = System.nanoTime();
                    int successful = 0;
                    for (int iteration = 0; iteration < batch; iteration++) {
                        if (fixture.matching(predicate, fallback, reserve)) successful++;
                    }
                    long elapsed = System.nanoTime() - start;
                    long bytes = bean.getThreadAllocatedBytes(thread) - allocated;
                    if (successful != (reserve == 36 ? 0 : batch)) throw new AssertionError(scenario);
                    observed += successful;
                    if (sample >= 0) OUTPUT.printf(Locale.ROOT, "MATERIAL,%s,%s,%d,%d,%d,%d%n",
                            args[0], scenario, sample, batch, elapsed, bytes);
                }
                int[] evaluations = {0};
                fixture.matching(stack -> { evaluations[0]++; return stack.is(Items.STONE); }, fallback, reserve);
                OUTPUT.printf("PREDICATES,%s,%s,%d%n", args[0], scenario, evaluations[0]);
                if (!fixture.traffic.isEmpty() || !fixture.requests.isEmpty()) throw new AssertionError("Unexpected inventory action");
            }
        }
        OUTPUT.println("MATERIAL_VERIFIED," + observed);
    }
}
//#endif
