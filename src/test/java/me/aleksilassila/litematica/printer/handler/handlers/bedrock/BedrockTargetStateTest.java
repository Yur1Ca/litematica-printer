package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockTargetStateTest {
    @Test
    void observationClockAdvancesRegardlessOfStatusAndIsIdempotentWithinATick() {
        BedrockTargetState state = new BedrockTargetState();

        state.beginTick(100);
        assertEquals(0, state.tickTimes());

        state.setStatus(BedrockTarget.Status.EXTENDED);
        state.beginTick(101);
        assertEquals(1, state.tickTimes());
        state.beginTick(101);
        assertEquals(1, state.tickTimes());

        state.setStatus(BedrockTarget.Status.NEEDS_WAITING);
        state.beginTick(109);
        assertEquals(9, state.tickTimes());
    }

    @Test
    void resetAttemptClearsOnlyAttemptState() {
        BedrockTargetState state = new BedrockTargetState();
        state.setStatus(BedrockTarget.Status.NEEDS_WAITING);
        state.setHasTried(true);
        state.setExecuteTick(7);
        state.setInitializeTick(3);
        state.incrementStuckTicks();

        state.resetAttempt();

        assertEquals(0, state.tickTimes());
        assertEquals(-1, state.executeTick());
        assertEquals(-1, state.initializeTick());
        assertEquals(0, state.stuckTicks());
        assertFalse(state.hasTried());
        assertEquals(BedrockTarget.Status.NEEDS_WAITING, state.status());
    }

    @Test
    void resettingAnAttemptDoesNotAdvanceItsClockAgainInTheSameRuntimeTick() {
        BedrockTargetState state = new BedrockTargetState();
        state.beginTick(10);
        state.beginTick(14);
        state.markDispatched();
        assertEquals(4, state.executeTick());
        assertTrue(state.hasTried());
        state.resetAttempt();

        state.beginTick(14);

        assertEquals(0, state.tickTimes());
        state.beginTick(15);
        assertEquals(1, state.tickTimes());
    }
}
