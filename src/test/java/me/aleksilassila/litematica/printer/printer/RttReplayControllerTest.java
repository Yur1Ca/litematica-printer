package me.aleksilassila.litematica.printer.printer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RttReplayControllerTest {
    @Test
    void convertsRoundTripMillisToTicksWithSafetyFactor() {
        assertEquals(0, RttReplayController.intervalTicksFor(0.0D, 100));
        assertEquals(1, RttReplayController.intervalTicksFor(50.0D, 100));
        assertEquals(2, RttReplayController.intervalTicksFor(100.0D, 100));
        assertEquals(4, RttReplayController.intervalTicksFor(200.0D, 100));
        assertEquals(10, RttReplayController.intervalTicksFor(500.0D, 100));
        assertEquals(3, RttReplayController.intervalTicksFor(100.0D, 125));
    }

    @Test
    void capsExtremeLatencyAndRejectsNegativeSafety() {
        assertEquals(40, RttReplayController.intervalTicksFor(10_000.0D, 300));
        assertEquals(0, RttReplayController.intervalTicksFor(250.0D, -1));
    }
}
