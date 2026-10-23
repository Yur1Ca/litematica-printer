//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import com.sun.management.ThreadMXBean;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.management.ManagementFactory;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.registries.BuiltInRegistries;

/** Measures the real refresh/qualification/analysis/sort path; no packet timing is changed. */
public final class MineCandidateRefreshBenchmark {
    private static final java.io.PrintStream OUTPUT = System.out;
    private static volatile long observed;

    public static void main(String[] args) throws Exception {
        InventoryRegressionTest.bootstrap();
        if (args.length == 1 && args[0].equals("verify")) {
            var tests = new MineCandidateRefreshTest();
            tests.refreshReplacesQueuedStateAndDropsRemovedOrUnbreakableTargets();
            tests.refreshStillChecksCurrentRangeAndRestrictions();
            tests.refreshPreservesDistanceThenCoordinateOrder();
            tests.sameTickToolSwitchAndReplacementStillInvalidateAnalysis();
            OUTPUT.println("FIXTURE_VERIFIED");
            return;
        }
        String label = args[0];
        int count = Integer.parseInt(args[1]);
        boolean creative = args[2].equals("creative");
        int fork = Integer.parseInt(args[3]);
        int warmups = Integer.parseInt(args[4]);
        int samples = Integer.parseInt(args[5]);
        if (count < 1 || count > 4096) throw new IllegalArgumentException("Candidate count must be 1..4096");
        var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        try (var fixture = new MineCandidateRefreshFixture(creative)) {
            fixture.seed(count, true);
            List<MineCandidateRefreshFixture.TargetView> expected = fixture.views(fixture.refresh());
            if (expected.size() != count) throw new AssertionError("Fixture candidates filtered unexpectedly");
            OUTPUT.printf(Locale.ROOT, "OUTPUT_SHA256,%s,%s,%d,%s%n", label,
                    creative ? "creative" : "survival", count, fingerprint(expected));
            for (int sample = -warmups; sample < samples; sample++) {
                fixture.beginTick();
                fixture.level.reads = 0;
                long allocated = bean.getThreadAllocatedBytes(thread);
                long start = System.nanoTime();
                List<?> targets = fixture.refresh();
                long elapsed = System.nanoTime() - start;
                long bytes = bean.getThreadAllocatedBytes(thread) - allocated;
                long reads = fixture.level.reads;
                if (!expected.equals(fixture.views(targets))) throw new AssertionError("Ordered target output changed");
                observed += targets.size();
                if (sample >= 0) OUTPUT.printf(Locale.ROOT, "SAMPLE,%s,%d,%s,%d,%d,%d,%d,%d%n",
                        label, fork, creative ? "creative" : "survival", count, sample, elapsed, bytes, reads);
            }
        }
        OUTPUT.println("MEASUREMENT_VERIFIED," + observed);
    }

    private static String fingerprint(List<MineCandidateRefreshFixture.TargetView> targets) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(bytes)) {
            for (var target : targets) {
                data.writeLong(target.pos().asLong());
                data.writeUTF(target.state().toString());
                data.writeInt(Float.floatToIntBits(target.currentProgress()));
                data.writeInt(Float.floatToIntBits(target.progress()));
                data.writeUTF(BuiltInRegistries.ITEM.getKey(target.tool()).toString());
            }
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    }
}
//#endif
