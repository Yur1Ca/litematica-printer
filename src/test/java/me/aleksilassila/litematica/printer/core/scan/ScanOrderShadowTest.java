package me.aleksilassila.litematica.printer.core.scan;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScanOrderShadowTest {
    @Test void constructionIsLazyAndDoesNotCreateAWorker() {
        var gate = new WorkerGate();
        try (var shadow = new ScanOrderShadow(gate)) {
            assertEquals(0, gate.created.get());
            shadow.reset();
            assertEquals(0, gate.created.get());
            assertNull(shadow.poll());
        }
    }

    @Test void revalidatesLifetimeAfterComputationHasAlreadyFinished() throws Exception {
        var gate = new WorkerGate();
        try (var shadow = new ScanOrderShadow(gate)) {
            var handle = handle(1);
            var empty = new OrderedPositions(new long[0], 0, 0, 0);
            shadow.submit(handle, new long[0], empty, 0, 0, 0);
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
            shadow.submit(handle, new long[0], empty, 0, 0, 0);
            gate.release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (shadow.overwrittenResults() == 0 && System.nanoTime() < deadline) LockSupport.parkNanos(100_000);
            assertEquals(1, shadow.overwrittenResults(), "both computations must finish before owner closes");
            handle.close();
            assertEquals(ScanOrderShadow.Status.STALE, await(shadow).status());
        } finally { gate.release.countDown(); }
    }

    @Test void copiesInputAndComparesTheCompleteOrderOnOneBackgroundThread() throws Exception {
        var gate = new WorkerGate();
        long[] input = {1L << 38, 0L, 2L << 38};
        try (var shadow = new ScanOrderShadow(gate)) {
            assertTrue(shadow.submit(handle(1), input, new OrderedPositions(input, 0, 0, 0), 0, 0, 0));
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
            input[0] = 999;
            assertNull(shadow.poll(), "submission must return while the worker is still blocked");
            gate.release.countDown();
            var result = await(shadow);
            assertEquals(ScanOrderShadow.Status.MATCH, result.status());
            assertEquals(3, result.positions());
            assertNotSame(Thread.currentThread(), gate.thread.get());
            assertEquals(1, gate.created.get());
            assertTrue(result.readyNanos() >= result.computeNanos());
        } finally { gate.release.countDown(); }
    }

    @Test void detectsAnOrderMismatchEvenWithIdenticalCountAndPositionSet() {
        long[] input = {0L, 1L << 38, 2L << 38};
        try (var shadow = new ScanOrderShadow()) {
            // Same set and length, opposite distance order: count/XOR checks alone would miss this.
            assertTrue(shadow.submit(handle(1), input, new OrderedPositions(input, 2, 0, 0), 0, 0, 0));
            assertEquals(ScanOrderShadow.Status.MISMATCH, await(shadow).status());
            assertTrue(shadow.submit(handle(2), input, new OrderedPositions(new long[]{0L}, 0, 0, 0), 0, 0, 0));
            assertEquals(ScanOrderShadow.Status.MISMATCH, await(shadow).status());
            assertTrue(shadow.submit(handle(3), input, new OrderedPositions(input, 0, 0, 0), 0, 0, 0));
            assertEquals(ScanOrderShadow.Status.MATCH, await(shadow).status());
        }
    }

    @Test void retainsOnlyLatestWaitingRequestWithoutCancellingItsLiveScanHandle() throws Exception {
        var gate = new WorkerGate();
        try (var shadow = new ScanOrderShadow(gate)) {
            var first = handle(1); var second = handle(2); var third = handle(3);
            var empty = new OrderedPositions(new long[0], 0, 0, 0);
            assertTrue(shadow.submit(first, new long[0], empty, 0, 0, 0));
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
            assertTrue(shadow.submit(second, new long[0], empty, 0, 0, 0));
            assertTrue(shadow.submit(third, new long[0], empty, 0, 0, 0));
            assertEquals(1, shadow.queuedRequests());
            assertEquals(1, shadow.supersededRequests());
            assertFalse(second.isCancelled(), "diagnostics do not own the real scan lifetime");
            gate.release.countDown();
            ScanOrderShadow.Result result;
            do { result = await(shadow); } while (result.generation().sequence() != 3);
            assertEquals(ScanOrderShadow.Status.MATCH, result.status());
            assertEquals(1, gate.created.get());
        } finally { gate.release.countDown(); }
    }

    @Test void closedOwnerOrNewWorldRejectsRunningResultAndAllowsNewRequests() throws Exception {
        for (boolean worldReset : new boolean[]{false, true}) {
            var gate = new WorkerGate();
            try (var shadow = new ScanOrderShadow(gate)) {
                var old = handle(1);
                var empty = new OrderedPositions(new long[0], 0, 0, 0);
                assertTrue(shadow.submit(old, new long[0], empty, 0, 0, 0));
                assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
                if (worldReset) shadow.reset(); else old.close();
                gate.release.countDown();
                assertEquals(ScanOrderShadow.Status.STALE, await(shadow).status());
                assertTrue(shadow.submit(handle(2), new long[0], empty, 0, 0, 0));
                assertEquals(ScanOrderShadow.Status.MATCH, await(shadow).status());
                assertEquals(1, gate.created.get());
            } finally { gate.release.countDown(); }
        }
    }

    @Test void failureToStartWorkerDoesNotEscapeOrPreventLaterComparison() {
        var attempts = new AtomicInteger();
        try (var shadow = new ScanOrderShadow(task -> {
            if (attempts.getAndIncrement() == 0) throw new IllegalStateException("thread startup");
            Thread thread = new Thread(task, "shadow-recovered-test");
            thread.setDaemon(true);
            return thread;
        })) {
            var empty = new OrderedPositions(new long[0], 0, 0, 0);
            assertFalse(shadow.submit(handle(1), new long[0], empty, 0, 0, 0));
            assertEquals(ScanOrderShadow.Status.FAILED, await(shadow).status());
            assertTrue(shadow.submit(handle(2), new long[0], empty, 0, 0, 0));
            assertEquals(ScanOrderShadow.Status.MATCH, await(shadow).status());
        }
    }

    @Test void closeDropsWaitingWorkAndNeverReleasesTheGameplayHandle() throws Exception {
        var gate = new WorkerGate();
        var shadow = new ScanOrderShadow(gate);
        var active = handle(1);
        try {
            var empty = new OrderedPositions(new long[0], 0, 0, 0);
            assertTrue(shadow.submit(active, new long[0], empty, 0, 0, 0));
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
            assertTrue(shadow.submit(handle(2), new long[0], empty, 0, 0, 0));
            shadow.close();
            assertEquals(0, shadow.queuedRequests());
            assertFalse(active.isCancelled());
            assertFalse(shadow.submit(handle(3), new long[0], empty, 0, 0, 0));
            assertNull(shadow.poll());
        } finally { gate.release.countDown(); shadow.close(); }
    }

    private static ScanHandle handle(long sequence) {
        return new ScanHandle(new ScanGeneration(RuntimeEpoch.INITIAL, 0, 0, sequence));
    }

    private static ScanOrderShadow.Result await(ScanOrderShadow shadow) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        ScanOrderShadow.Result result;
        while ((result = shadow.poll()) == null && System.nanoTime() < deadline) LockSupport.parkNanos(100_000);
        assertNotNull(result, "background comparison did not complete");
        return result;
    }

    private static final class WorkerGate implements ThreadFactory {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger created = new AtomicInteger();
        final AtomicReference<Thread> thread = new AtomicReference<>();
        @Override public Thread newThread(Runnable task) {
            created.incrementAndGet();
            Thread worker = new Thread(() -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException ignored) { return; }
                task.run();
            }, "shadow-gated-test");
            worker.setDaemon(true);
            thread.set(worker);
            return worker;
        }
    }
}
