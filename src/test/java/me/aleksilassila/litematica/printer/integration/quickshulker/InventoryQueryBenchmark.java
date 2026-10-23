//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import me.aleksilassila.litematica.printer.utils.mods.TakeItOutQueryFixture;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit query-only benchmark. Lookup/decode instrumentation is disabled in timed fixtures. */
public final class InventoryQueryBenchmark {
    private static final PrintStream OUTPUT = System.out;
    private static volatile long consumed;
    private static com.sun.management.ThreadMXBean allocations;
    private static long thread;
    private static int warmup, iterations;

    @FunctionalInterface private interface Query { long run() throws Exception; }

    public static void main(String[] args) throws Exception {
        CancelledSwapLifecycleTest.bootstrap();
        warmup = args.length > 0 ? Integer.parseInt(args[0]) : 2000;
        iterations = args.length > 1 ? Integer.parseInt(args[1]) : 5000;
        if (warmup < 0 || iterations <= 0) throw new IllegalArgumentException("Invalid iteration counts");
        var bean = ManagementFactory.getThreadMXBean();
        allocations = bean instanceof com.sun.management.ThreadMXBean supported && supported.isThreadAllocatedMemorySupported() ? supported : null;
        if (allocations != null && !allocations.isThreadAllocatedMemoryEnabled()) allocations.setThreadAllocatedMemoryEnabled(true);
        thread = Thread.currentThread().threadId();
        OUTPUT.println("scenario,iterations,ns_per_query,bytes_per_query,checksum");
        quick(1, 1); quick(12, 4); quick(27, 8);
        takeAvailable(0); takeAvailable(16); takeAvailable(256);
        takeEmpty(64);
    }

    private static void quick(int boxes, int requested) throws Exception {
        try (var fixture = new QuickShulkerQueryFixture(false)) {
            Item[] candidates = {Items.DIRT, Items.GLASS, Items.DIAMOND, Items.GRAVEL,
                    Items.OAK_PLANKS, Items.SAND, Items.OBSIDIAN, Items.GOLD_BLOCK};
            var wanted = List.of(candidates).subList(0, requested);
            for (int i = 0; i < boxes; i++) {
                var contents = new ItemStack[27];
                for (int inner = 0; inner < contents.length; inner++) contents[inner] = new ItemStack(Items.STONE, 64);
                fixture.slot(9 + i, QuickShulkerQueryFixture.box(contents));
            }
            assertFalse(fixture.query(wanted, false));
            measure("shulker_" + boxes + "boxes_" + requested + "missing", () -> fixture.query(wanted, false) ? 1 : 0);
            assertTrue(fixture.attempts.isEmpty(), "Missing-material queries must not send an open request");
        }
    }

    private static void takeAvailable(int count) throws Exception {
        try (var fixture = new TakeItOutQueryFixture(false)) {
            var entries = new ArrayList<TakeItOutQueryFixture.Entry>();
            for (int i = 0; i < count; i++) entries.add(new TakeItOutQueryFixture.Entry(new ItemStack(i % 2 == 0 ? Items.STONE : Items.DIRT), i + 1));
            TakeItOutQueryFixture.Client.WORLD_CONTAINER_ITEMS = entries;
            int expected = count == 0 ? 0 : count == 1 ? 1 : 2;
            assertEquals(expected, fixture.available().size());
            measure("take_available_" + count, () -> fixture.available().size());
            assertEquals(expected, fixture.available().size());
        }
    }

    private static void takeEmpty(int count) throws Exception {
        try (var fixture = new TakeItOutQueryFixture(false)) {
            var entries = new ArrayList<TakeItOutQueryFixture.Entry>();
            for (int i = 0; i < count - 1; i++) entries.add(new TakeItOutQueryFixture.Entry(new ItemStack(Items.STONE), 64));
            var empty = new ItemStack(Items.SHULKER_BOX);
            empty.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("selected empty box"));
            entries.add(new TakeItOutQueryFixture.Entry(empty, 2));
            var wanted = new ItemStack(Items.SHULKER_BOX);
            assertEquals(empty.getHoverName(), fixture.empty(wanted, entries).getHoverName());
            measure("take_empty_" + count, () -> fixture.empty(wanted, entries).getCount());
            assertEquals(empty.getHoverName(), fixture.empty(wanted, entries).getHoverName());
        }
    }

    private static void measure(String scenario, Query query) throws Exception {
        for (int i = 0; i < warmup; i++) consumed += query.run();
        long before = allocations == null ? 0 : allocations.getThreadAllocatedBytes(thread);
        long start = System.nanoTime(), checksum = 0;
        for (int i = 0; i < iterations; i++) checksum += query.run();
        long elapsed = System.nanoTime() - start;
        long bytes = allocations == null ? -1 : allocations.getThreadAllocatedBytes(thread) - before;
        consumed = checksum;
        OUTPUT.printf(java.util.Locale.ROOT, "%s,%d,%.1f,%.1f,%d%n", scenario, iterations,
                (double) elapsed / iterations, bytes < 0 ? -1.0 : (double) bytes / iterations, checksum);
    }
}
//#endif
