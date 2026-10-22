package me.aleksilassila.litematica.printer.core.diagnostics;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming.*;

class RuntimeTimingTest {
    @Test void disabledDoesNotReadClockOrCreateSamples() {
        var timing = new RuntimeTiming(false, () -> { throw new AssertionError("clock read"); });
        for (int i = 0; i < 1000; i++) {
            timing.finish("print", Stage.CLASSIFICATION, timing.start());
            timing.count("print", Reason.CURSOR_CREATED, 1);
        }
        assertFalse(timing.enabled());
        assertTrue(timing.snapshot().samples().isEmpty());
        assertTrue(timing.snapshot().counters().isEmpty());
        timing.reset();
        assertSame(RuntimeTiming.disabled(), RuntimeTiming.disabled());
    }

    @Test void recordsInclusiveNestedCostsAndKeepsOwnersSeparate() {
        var clock = new AtomicLong(100);
        var timing = new RuntimeTiming(true, clock::get);
        long outer = timing.start();
        clock.set(110);
        long inner = timing.start();
        clock.set(140);
        timing.finish("print", Stage.INDEX_SORT, inner);
        clock.set(150);
        timing.finish("print", Stage.CURSOR_BUILD, outer);
        clock.set(160);
        timing.finish("print", Stage.INDEX_SORT, inner);
        timing.finish("fill", Stage.CURSOR_BUILD, inner);
        timing.count("print", Reason.CURSOR_CREATED, 1);
        timing.count("print", Reason.CURSOR_CREATED, 2);
        var snapshot = timing.snapshot();
        assertTrue(timing.enabled());
        assertEquals(new Sample("print", Stage.INDEX_SORT, 2, 80, 50),
                snapshot.samples().stream().filter(s -> s.stage() == Stage.INDEX_SORT).findFirst().orElseThrow());
        assertEquals(50, snapshot.samples().stream().filter(s -> s.owner().equals("print")
                && s.stage() == Stage.CURSOR_BUILD).findFirst().orElseThrow().totalNanos());
        assertEquals(3, snapshot.counters().get(0).count());
        timing.reset();
        assertTrue(timing.snapshot().samples().isEmpty());
        assertEquals(3, snapshot.samples().size()); // immutable, independent of the next window
        assertThrows(UnsupportedOperationException.class, () -> snapshot.samples().clear());
    }

    @Test void ownerCardinalityIsBoundedAndZeroCountsDoNotCreateEntries() {
        var timing = new RuntimeTiming(true, () -> 5);
        timing.count("unused", Reason.CURSOR_CREATED, 0);
        assertTrue(timing.snapshot().counters().isEmpty());
        for (int i = 0; i < 200; i++) timing.finish("owner" + i, Stage.MODULE_TICK, 8);
        assertTrue(timing.snapshot().samples().size() <= 17);
        assertEquals(200, timing.snapshot().samples().stream().mapToLong(Sample::calls).sum());
        assertEquals(0, timing.snapshot().samples().stream().mapToLong(Sample::totalNanos).sum());
    }
}
