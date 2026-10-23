package me.aleksilassila.litematica.printer.handler.scan;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.core.scan.ScanGeneration;
import me.aleksilassila.litematica.printer.core.scan.ScanHandle;
import me.aleksilassila.litematica.printer.core.scan.ScanOrderShadow;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static me.aleksilassila.litematica.printer.handler.scan.IndexedPositionQueryParityTest.*;

class ScanOrderShadowLifecycleTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void backgroundNeverQueriesLiveStorageAndCannotDelaySynchronousCandidates() throws Exception {
        var gate = new Gate();
        var calls = new AtomicInteger();
        Thread owner = Thread.currentThread();
        SchematicPositionQuery query = (boxes, region, consumer) -> {
            assertSame(owner, Thread.currentThread());
            calls.incrementAndGet();
            consumer.accept(BlockPos.asLong(1, 0, 0));
            consumer.accept(0L);
            return 2;
        };
        try (var shadow = new ScanOrderShadow(gate::thread)) {
            var handle = new ScanHandle(new ScanGeneration(RuntimeEpoch.INITIAL, 0, 0, 1));
            var cursor = new IndexedPositionCursor(query, List.of(new PrinterBox(-2, -2, -2, 2, 2, 2)),
                    region(0, 0, 0, 2), RuntimeTiming.disabled(), "print", shadow, handle);
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
            assertCursorEquals(List.of(BlockPos.ZERO, new BlockPos(1, 0, 0)), cursor);
            assertNull(shadow.poll());
            gate.release.countDown();
            assertEquals(ScanOrderShadow.Status.MATCH, await(shadow).status());
            assertEquals(1, calls.get());
        } finally { gate.release.countDown(); }
    }

    @ParameterizedTest @ValueSource(strings = {"owner", "chunk", "intent", "world"})
    void lifecycleChangesDiscardOldComparisonAndKeepOtherFeaturesRunnable(String change) throws Exception {
        var gate = new Gate();
        boolean extra = Configs.Print.BREAK_EXTRA_BLOCK.getBooleanValue();
        Configs.Print.BREAK_EXTRA_BLOCK.setBooleanValue(false);
        try (var shadow = new ScanOrderShadow(gate::thread)) {
            var timing = new RuntimeTiming(true, System::nanoTime);
            var index = SparseScanLifecycleTest.readyIndex(List.of(BlockPos.ZERO));
            var store = new ScanSessionStore(index, timing, shadow);
            var near = new PrinterBox(-1, -1, -1, 1, 1, 1);
            var far = new PrinterBox(63, -1, 63, 65, 1, 65);
            var print = stored(store, "print", ScanIntent.PRINT, near, RuntimeEpoch.INITIAL);
            var fill = stored(store, "fill", ScanIntent.FILL, far, RuntimeEpoch.INITIAL);
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
            switch (change) {
                case "owner" -> store.resetOwner("print");
                case "chunk" -> store.invalidateChunk(0, 0);
                case "intent" -> store.resetIntent(ScanIntent.PRINT);
                case "world" -> store.close();
                default -> fail();
            }
            assertFalse(print.canScan(1000));
            assertEquals(!change.equals("world"), fill.canScan(1000));
            gate.release.countDown();
            assertEquals(ScanOrderShadow.Status.STALE, await(shadow).status());
            var fresh = stored(store, "print", ScanIntent.PRINT, near, RuntimeEpoch.INITIAL.next());
            assertTrue(fresh.canScan(1001));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (matches(timing) == 0 && System.nanoTime() < deadline) {
                store.collectComparisons();
                LockSupport.parkNanos(100_000);
            }
            assertEquals(1, matches(timing));
            store.close();
        } finally { gate.release.countDown(); Configs.Print.BREAK_EXTRA_BLOCK.setBooleanValue(extra); }
    }

    private static long matches(RuntimeTiming timing) {
        return timing.snapshot().counters().stream()
                .filter(c -> c.reason() == RuntimeTiming.Reason.SHADOW_MATCHED).mapToLong(RuntimeTiming.Counter::count).sum();
    }

    private static SectionScanSession stored(ScanSessionStore store, String owner, ScanIntent intent,
                                             PrinterBox box, RuntimeEpoch epoch) {
        return store.getOrCreate(owner, store.metrics(owner), intent, box, List.of(box), null,
                epoch, () -> 0, () -> 1);
    }

    private static ScanOrderShadow.Result await(ScanOrderShadow shadow) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        ScanOrderShadow.Result result;
        while ((result = shadow.poll()) == null && System.nanoTime() < deadline) LockSupport.parkNanos(100_000);
        assertNotNull(result);
        return result;
    }

    private static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread thread(Runnable task) {
            Thread thread = new Thread(() -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException ignored) { return; }
                task.run();
            }, "shadow-lifecycle-test");
            thread.setDaemon(true);
            return thread;
        }
    }
}
