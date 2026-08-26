package me.aleksilassila.litematica.printer.handler.handlers.print;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrintPlacementResultTest {
    @Test
    void unavailableMaterialWaitsForInventoryChangeInsteadOfHotRetrying() {
        assertFalse(PrintPlacementResult.materialUnavailable(false).shouldRetryTarget());
    }

    @Test
    void existingFailureDeferralAndCancellationRemainRetryable() {
        assertTrue(PrintPlacementResult.failure(false, false).shouldRetryTarget());
        assertTrue(PrintPlacementResult.deferred(false).shouldRetryTarget());
        assertTrue(PrintPlacementResult.cancelled(false).shouldRetryTarget());
    }
}
