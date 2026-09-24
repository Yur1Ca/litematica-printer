package me.aleksilassila.litematica.printer.handler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickSchedulerTest {
    @Test
    void pausesActionModulesWhileInventoryOrContainerIsOwned() {
        assertTrue(TickScheduler.shouldPauseActions(true, false));
        assertTrue(TickScheduler.shouldPauseActions(false, true));
        assertFalse(TickScheduler.shouldPauseActions(false, false));
    }
}
