package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockSchedulingPolicyTest {
    @Test
    void cleanupPressureReducesSubmissionGradually() {
        assertEquals(6, BedrockSchedulingPolicy.submitCap(6, 0));
        assertEquals(5, BedrockSchedulingPolicy.submitCap(6, 8));
        assertEquals(4, BedrockSchedulingPolicy.submitCap(6, 16));
        assertEquals(1, BedrockSchedulingPolicy.submitCap(6, 40));
        assertEquals(1, BedrockSchedulingPolicy.submitCap(1, 10_000));
    }

    @Test
    void onlyActionableMachineStatesConsumeActiveCapacity() {
        assertTrue(BedrockSchedulingPolicy.countsTowardsActiveCap(BedrockTarget.Status.UNINITIALIZED));
        assertTrue(BedrockSchedulingPolicy.countsTowardsActiveCap(BedrockTarget.Status.EXTENDED));
        assertTrue(BedrockSchedulingPolicy.countsTowardsActiveCap(BedrockTarget.Status.NEEDS_WAITING));
        assertTrue(BedrockSchedulingPolicy.countsTowardsActiveCap(BedrockTarget.Status.RETRACTING));
    }

    @Test
    void urgentLaneContainsOnlyExecuteAndRepowerStates() {
        assertTrue(BedrockSchedulingPolicy.isFastLane(BedrockTarget.Status.EXTENDED));
        assertTrue(BedrockSchedulingPolicy.isFastLane(BedrockTarget.Status.UNEXTENDED_WITHOUT_POWER_SOURCE));
        assertFalse(BedrockSchedulingPolicy.isFastLane(BedrockTarget.Status.UNINITIALIZED));
        assertFalse(BedrockSchedulingPolicy.isFastLane(BedrockTarget.Status.UNEXTENDED_WITH_POWER_SOURCE));
    }

    @Test
    void cleanupLimitScalesWithoutLosingBlockedDemandBonus() {
        assertEquals(48, BedrockSchedulingPolicy.cleanupLimit(6, 0, 48, 32));
        assertEquals(80, BedrockSchedulingPolicy.cleanupLimit(6, 100, 48, 32));
        assertEquals(50, BedrockSchedulingPolicy.cleanupLimit(10, 0, 48, 32));
    }
}
