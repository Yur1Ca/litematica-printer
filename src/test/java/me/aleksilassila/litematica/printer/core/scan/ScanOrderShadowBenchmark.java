//#if MC == 260100
package me.aleksilassila.litematica.printer.core.scan;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;

/** Explicit offline benchmark, never run as a JUnit test. No live-world or server-speed claim. */
public final class ScanOrderShadowBenchmark {
    private static volatile Object sink;
    private static final ThreadMXBean ALLOCATION = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    public static void main(String[] args) {
        int size = Integer.parseInt(args[0]), fork = Integer.parseInt(args[1]);
        int warmups = Integer.parseInt(args[2]), samples = Integer.parseInt(args[3]);
        long[] source = new long[size];
        for (int i = 0; i < size; i++) {
            int x = (i & 63) - 32, y = ((i >> 6) & 63) - 32, z = ((i >> 12) & 63) - 32;
            source[i] = ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
        }
        Random random = new Random(0x24C0DE);
        for (int i = size - 1; i > 0; i--) {
            int j = random.nextInt(i + 1); long value = source[i]; source[i] = source[j]; source[j] = value;
        }
        OrderedPositions expected = new OrderedPositions(source, 0, 0, 0);
        long fingerprint = 0xcbf29ce484222325L;
        for (int i = 0; i < size; i++) fingerprint = (fingerprint ^ expected.positionAt(i)) * 0x100000001b3L;
        System.out.println("ORDER " + size + " " + Long.toUnsignedString(fingerprint));
        ALLOCATION.setThreadAllocatedMemoryEnabled(true);
        var workerId = new AtomicLong();
        try (var shadow = new ScanOrderShadow(task -> {
            Thread thread = new Thread(() -> { workerId.set(Thread.currentThread().threadId()); task.run(); }, "shadow-benchmark");
            thread.setDaemon(true);
            return thread;
        })) {
            for (int i = -warmups; i < samples; i++) {
                // Alternate the order within a JVM as well as using independent forks.
                if ((i & 1) == 0) synchronous(source, fork, i);
                asynchronous(shadow, workerId, source, expected, fork, i);
                if ((i & 1) != 0) synchronous(source, fork, i);
            }
        }
    }

    private static void synchronous(long[] source, int fork, int sample) {
        long bytes = ALLOCATION.getCurrentThreadAllocatedBytes(), start = System.nanoTime();
        long[] captured = source.clone(); // Snapshot cost included in both paths.
        sink = new OrderedPositions(captured, 0, 0, 0);
        long elapsed = System.nanoTime() - start;
        if (sample >= 0) row(source.length, fork, "sync", sample, elapsed, elapsed,
                ALLOCATION.getCurrentThreadAllocatedBytes() - bytes, 0, 0, elapsed);
    }

    private static void asynchronous(ScanOrderShadow shadow, AtomicLong workerId, long[] source,
                                     OrderedPositions expected, int fork, int sample) {
        long workerBytes = workerId.get() == 0 ? 0 : ALLOCATION.getThreadAllocatedBytes(workerId.get());
        long bytes = ALLOCATION.getCurrentThreadAllocatedBytes(), start = System.nanoTime();
        long[] captured = source.clone();
        var handle = new ScanHandle(new ScanGeneration(RuntimeEpoch.INITIAL, 0, 0, sample + 10000));
        long submitted = System.nanoTime();
        if (!shadow.submit(handle, captured, expected, 0, 0, 0)) throw new AssertionError("rejected");
        long mainNanos = System.nanoTime() - start;
        long mainBytes = ALLOCATION.getCurrentThreadAllocatedBytes() - bytes;
        ScanOrderShadow.Result result;
        long deadline = start + TimeUnit.SECONDS.toNanos(10);
        while ((result = shadow.poll()) == null && System.nanoTime() < deadline) LockSupport.parkNanos(10_000);
        if (result == null || result.status() != ScanOrderShadow.Status.MATCH) throw new AssertionError("comparison failed: " + result);
        sink = result;
        // readyNanos is measured inside the worker; excludes the polling delay of this harness.
        long readyNanos = result.readyNanos() + submitted - start;
        if (sample >= 0) row(source.length, fork, "shadow", sample, mainNanos, readyNanos,
                mainBytes, ALLOCATION.getThreadAllocatedBytes(workerId.get()) - workerBytes,
                result.queueNanos(), result.computeNanos());
    }

    private static void row(int size, int fork, String mode, int sample, long main, long ready,
                            long bytes, long workerBytes, long queue, long compute) {
        System.out.printf("SHADOW,%d,%d,%s,%d,%d,%d,%d,%d,%d,%d%n",
                size, fork, mode, sample, main, ready, bytes, workerBytes, queue, compute);
    }
}
//#endif
