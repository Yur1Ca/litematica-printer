package me.aleksilassila.litematica.printer.handler.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming.*;

class ScanTimingParityTest {
    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void sparseFilteringAndTieOrderAreIdenticalWithDiagnosticsOnOrOff() {
        var index = new SectionPositionIndex();
        for (int x = -20; x <= 20; x++) for (int z = -20; z <= 20; z++) {
            index.add(BlockPos.asLong(x, -1, z));
        }
        var boxes = List.of(new PrinterBox(-3, -1, -2, 1, -1, 2), new PrinterBox(-1, -1, -2, 3, -1, 2));
        var region = ScanRegion.from(new PrinterBox(-3, -1, -2, 3, -1, 2), null);
        var clock = new AtomicLong();
        var timing = new RuntimeTiming(true, clock::incrementAndGet);
        var expected = drain(new IndexedPositionCursor(index, boxes, region));
        var actual = drain(new IndexedPositionCursor(index, boxes, region, timing, "print"));
        assertEquals(expected, actual);
        assertEquals(35, actual.size());
        assertEquals(new BlockPos(0, -1, 0), actual.get(0));
        assertEquals(new BlockPos(-1, -1, 0), actual.get(1));
        assertEquals(1024, count(timing, Reason.INDEX_POSITIONS_VISITED));
        assertEquals(35, count(timing, Reason.INDEX_CANDIDATES));
        assertEquals(3, timing.snapshot().samples().size());
        assertTrue(timing.snapshot().samples().stream().allMatch(sample -> sample.calls() == 1 && sample.totalNanos() == 1));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void pauseRestartDirtyAndCloseKeepTheSameProgress(boolean enabled) {
        var clock = new AtomicLong();
        var timing = new RuntimeTiming(enabled, clock::incrementAndGet);
        var metrics = new ScanMetricsAccumulator(timing, "mine");
        var box = new PrinterBox(-3, 0, -3, 3, 0, 3);
        var session = new SectionScanSession(ScanRegion.from(box, null), List.of(box), ScanIntent.MINE, metrics);
        var source = new Observation();
        assertNull(session.next(source, 10, () -> true, pos -> true, false));
        assertTrue(session.wasPaused());
        assertEquals(8, metrics.scannedBlocks);
        source.flags = ScanFlags.WORLD_NON_AIR;
        assertNotNull(session.next(source, 10, () -> false, pos -> true, false));
        assertEquals(9, metrics.scannedBlocks);
        assertEquals(0, metrics.completedPasses);
        while (session.next(source, 10, () -> false, pos -> true, true) != null) { }
        assertEquals(49, metrics.scannedBlocks);
        assertEquals(1, metrics.completedPasses);
        assertFalse(session.canScan(10));
        assertTrue(session.canScan(11));
        session.invalidate(BlockPos.ZERO);
        assertEquals(BlockPos.ZERO, session.next(source, 11, () -> false, pos -> true, true).pos());
        session.close();
        session.invalidate(BlockPos.ZERO);
        assertFalse(session.canScan(1000));
        assertFalse(session.hasPendingSource(1000));
        assertEquals(enabled ? 1 : 0, count(timing, Reason.PASS_RESTART));
        assertEquals(enabled ? 2 : 0, count(timing, Reason.CURSOR_CREATED));
        if (!enabled) assertEquals(0, clock.get());
    }

    @Test void classificationFailureIsMeasuredWithoutSwallowingOrReplacingTheException() {
        var timing = new RuntimeTiming(true, new AtomicLong()::incrementAndGet);
        var box = new PrinterBox(0, 0, 0, 0, 0, 0);
        var session = new SectionScanSession(ScanRegion.from(box, null), List.of(box), ScanIntent.MINE,
                new ScanMetricsAccumulator(timing, "mine"));
        var source = new Observation();
        source.failure = new IllegalStateException("observation");
        assertSame(source.failure, assertThrows(IllegalStateException.class,
                () -> session.next(source, 1, () -> false, pos -> true, true)));
        assertEquals(1, timing.snapshot().samples().stream().filter(s -> s.stage() == Stage.CLASSIFICATION)
                .findFirst().orElseThrow().calls());
    }

    @Test void narrowEpochSupplierStillInvalidatesPreviouslyCreatedWork() throws Exception {
        var timing = new RuntimeTiming(true, new AtomicLong()::incrementAndGet);
        var cache = new ScanCache(timing);
        var epoch = new AtomicReference<>(RuntimeEpoch.INITIAL);
        var engine = new ScanEngine(epoch::get, () -> null, cache);
        engine.beginTick(null, null, 10);
        var box = new PrinterBox(0, 0, 0, 1, 1, 1);
        var stream = engine.rawIterable("mine", box, null, 100, pos -> true);
        var oldIterator = stream.iterator();
        epoch.set(epoch.get().next());
        engine.beginTick(null, null, 10); // same game time, different session
        assertFalse(oldIterator.hasNext());
        assertEquals(ScanAvailability.COMPLETE, ((ScanCandidateIterable) stream).availability());
        assertSame(timing, engine.timing());
        assertTrue(timing.snapshot().samples().stream().anyMatch(s -> s.stage() == Stage.CANDIDATE_SETUP));
        assertTrue(timing.snapshot().samples().stream().anyMatch(s -> s.stage() == Stage.CANDIDATE_LOOKUP));
    }

    private static List<BlockPos> drain(PositionCursor cursor) {
        var values = new ArrayList<BlockPos>();
        var pos = new BlockPos.MutableBlockPos();
        while (cursor.poll(pos) == PositionCursor.PollResult.AVAILABLE) values.add(pos.immutable());
        assertTrue(cursor.isComplete());
        assertEquals(Long.MAX_VALUE, cursor.peekDistanceSqr());
        return values;
    }
    private static long count(RuntimeTiming timing, Reason reason) {
        return timing.snapshot().counters().stream().filter(c -> c.reason() == reason).mapToLong(Counter::count).sum();
    }
    private static final class Observation implements WorldObservationPort {
        byte flags;
        RuntimeException failure;
        @Override public boolean hasChunk(int x, int z) { return true; }
        @Override public byte classify(ScanIntent intent, BlockPos pos, boolean extra) {
            if (this.failure != null) throw this.failure;
            return this.flags;
        }
        @Override public BlockState worldState(BlockPos pos) { throw new AssertionError(); }
        @Override public BlockState schematicState(BlockPos pos) { throw new AssertionError(); }
        @Override public boolean hasFillSupport(BlockPos pos) { throw new AssertionError(); }
    }
}
