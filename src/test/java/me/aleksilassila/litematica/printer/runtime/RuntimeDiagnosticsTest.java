package me.aleksilassila.litematica.printer.runtime;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeScope;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeDiagnosticsTest {
    @Test void epochOutputDoesNotInflateTheActiveTickOrTheFollowingWindow() {
        var clock = new AtomicLong(100);
        var timing = new RuntimeTiming(true, clock::get);
        var lines = new ArrayList<String>();
        var diagnostics = new RuntimeDiagnostics(timing, line -> {
            lines.add(line);
            clock.addAndGet(1_000_000); // slow logger, not gameplay
        }, 2);
        timing.finish("print", RuntimeTiming.Stage.MODULE_TICK, 90);
        long tickStart = diagnostics.tickStarted();
        clock.addAndGet(20);
        diagnostics.onEpochChanged(new RuntimeEvent.EpochChanged(
                RuntimeEpoch.INITIAL, RuntimeEpoch.INITIAL.next(), "client_scope_changed"));
        clock.addAndGet(30);
        diagnostics.tickCompleted(RuntimeEpoch.INITIAL.next(), tickStart);
        var sample = timing.snapshot().samples().get(0);
        assertEquals(50, sample.totalNanos(), "The old epoch's logger must not become gameplay time in the new epoch");
        assertEquals("runtime", sample.owner());
        diagnostics.tickCompleted(RuntimeEpoch.INITIAL.next());
        long nextStart = diagnostics.tickStarted();
        clock.addAndGet(7);
        diagnostics.tickCompleted(RuntimeEpoch.INITIAL.next(), nextStart);
        assertEquals(7, timing.snapshot().samples().get(0).totalNanos());
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("epoch=0"));
        assertTrue(lines.get(1).contains("epoch=1"));
    }

    @Test void windowsAreBoundedAndEpochsCannotShareSamples() {
        var lines = new ArrayList<String>();
        var timing = new RuntimeTiming(true, () -> 1_000_000);
        var diagnostics = new RuntimeDiagnostics(timing, lines::add, 2);
        timing.finish("print", RuntimeTiming.Stage.CURSOR_BUILD, 0);
        timing.count("print", RuntimeTiming.Reason.CURSOR_CREATED, 2);
        diagnostics.tickCompleted(RuntimeEpoch.INITIAL);
        assertTrue(lines.isEmpty());
        diagnostics.tickCompleted(RuntimeEpoch.INITIAL);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("CURSOR_BUILD calls=1 totalMs=1.000 maxMs=1.000"));
        assertTrue(lines.get(0).contains("CURSOR_CREATED=2"));
        timing.finish("mine", RuntimeTiming.Stage.TRENCH_DISCOVERY, 0);
        var scope = new RuntimeScope();
        scope.register(diagnostics);
        scope.changeEpoch(new RuntimeEvent.EpochChanged(RuntimeEpoch.INITIAL, RuntimeEpoch.INITIAL.next(), "switch"));
        assertEquals(2, lines.size());
        assertTrue(timing.snapshot().samples().isEmpty());
        diagnostics.tickCompleted(RuntimeEpoch.INITIAL.next());
        assertEquals(2, lines.size());
        diagnostics.tickCompleted(RuntimeEpoch.INITIAL.next());
        assertEquals(3, lines.size());
        assertFalse(lines.get(2).contains("mine/"));
    }

    @Test void disabledDiagnosticsNeverLogAndBrokenLoggerCannotBreakWork() {
        var quiet = new RuntimeDiagnostics(new RuntimeTiming(false, () -> {
            fail("Disabled diagnostics must not read a clock");
            return 0L;
        }), line -> fail(line), 1);
        quiet.tickCompleted(RuntimeEpoch.INITIAL, quiet.tickStarted());
        quiet.epochEnded(RuntimeEpoch.INITIAL);
        var timing = new RuntimeTiming(true, () -> 10);
        var broken = new RuntimeDiagnostics(timing, line -> { throw new IllegalStateException("logger"); }, 1);
        timing.finish("print", RuntimeTiming.Stage.MODULE_TICK, 0);
        assertDoesNotThrow(() -> broken.tickCompleted(RuntimeEpoch.INITIAL));
        assertTrue(timing.snapshot().samples().isEmpty());
    }
}
