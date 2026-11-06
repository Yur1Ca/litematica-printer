package me.aleksilassila.litematica.printer.integration.voidwarehouse;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VoidWarehousePlacementDiagnosticsTest {
    @Test
    void activeWindowSummarizesEveryResultAndResponseDelayOnce() {
        var diagnostics = new VoidWarehousePlacementDiagnostics();
        diagnostics.reset(0);
        for (int status : new int[]{0, 3, 6, 4, 2, 1, 5}) diagnostics.recordSent(0);
        int now = 1;
        for (int status : new int[]{0, 3, 6, 4, 2, 1, 5}) {
            diagnostics.recordResult(status, 0, now++);
        }

        assertNull(diagnostics.poll(199, 2));
        var summary = diagnostics.poll(200, 2);
        assertNotNull(summary);
        assertEquals(200, summary.ticks());
        assertEquals(7, summary.sent());
        assertEquals(1, summary.ok());
        assertEquals(1, summary.rejected());
        assertEquals(1, summary.stale());
        assertEquals(1, summary.throttled());
        assertEquals(1, summary.empty());
        assertEquals(2, summary.other());
        assertEquals(2, summary.pending());
        assertEquals(4.0, summary.averageResponseTicks());
        assertEquals(7, summary.maxResponseTicks());
        assertNull(diagnostics.poll(200, 2));
        assertNull(diagnostics.poll(400, 2), "Pending alone must not emit idle summaries");
    }

    @Test
    void lateResultBelongsToItsCurrentWindowWithoutInventingAnotherSend() {
        var diagnostics = new VoidWarehousePlacementDiagnostics();
        diagnostics.reset(1000);
        diagnostics.recordSent(1199);
        var sent = diagnostics.poll(1200, 1);
        assertNotNull(sent);
        assertEquals(1, sent.sent());
        assertEquals(0.0, sent.averageResponseTicks());

        diagnostics.recordResult(0, 1199, 1201);
        var received = diagnostics.poll(1400, 0);
        assertNotNull(received);
        assertEquals(0, received.sent());
        assertEquals(1, received.ok());
        assertEquals(2.0, received.averageResponseTicks());
        assertEquals(2, received.maxResponseTicks());
    }

    @Test
    void resetDiscardsOldSessionActivityAndStartsANewWindow() {
        var diagnostics = new VoidWarehousePlacementDiagnostics();
        diagnostics.recordSent(10);
        diagnostics.recordResult(3, 10, 12);
        diagnostics.reset(20);
        assertNull(diagnostics.poll(220, 0));

        diagnostics.recordSent(221);
        diagnostics.recordResult(0, 221, 221);
        var summary = diagnostics.poll(420, 0);
        assertNotNull(summary);
        assertEquals(1, summary.sent());
        assertEquals(1, summary.ok());
        assertEquals(0, summary.rejected());
        assertEquals(0.0, summary.averageResponseTicks());
        assertEquals(0, summary.maxResponseTicks());
    }

    @Test
    void pendingCountReadsTheExistingLedgerWithoutChangingRequestEligibility() {
        var state = new VoidWarehousePlacementState<String>();
        state.receiveHello(state.beginHello(0), true, 0);
        assertEquals(0, state.pendingCount());
        int nonce = state.beginRequest(List.of("minecraft:stone"), "target", 0);
        assertEquals(1, state.pendingCount());
        assertFalse(state.canRequest(List.of("minecraft:stone"), "target", 0));
        state.receiveResult(nonce, 0, 1);
        assertEquals(0, state.pendingCount());
        assertTrue(state.canRequest(List.of("minecraft:stone"), "target", 1));
    }
}
