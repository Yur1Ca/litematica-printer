package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BedrockThroughputSchedulerTest {
    @Test void manualIntervalIsBetweenSuccessfulStartsRatherThanScanWindows() {
        var scheduler = new BedrockThroughputScheduler<String>();
        scheduler.beginTick(10, 6, 2, true);
        assertEquals(1, scheduler.availableStarts());
        assertTrue(scheduler.canStart("A"));
        assertTrue(scheduler.canStart("B"), "A rejected candidate does not consume a start");
        scheduler.started("B");
        assertEquals(0, scheduler.availableStarts());
        scheduler.beginTick(10, 6, 2, true);
        assertEquals(0, scheduler.availableStarts(), "Repeated begin/finish cannot reopen this tick");
        scheduler.beginTick(11, 6, 2, true);
        assertTrue(scheduler.canStart("B"), "Started owners continue during the new-start interval");
        assertFalse(scheduler.canStart("C"));
        scheduler.beginTick(12, 6, 2, true);
        assertTrue(scheduler.canStart("C"));
    }

    @Test void idleTimeNeverAccumulatesAWholeBatchOfStarts() {
        var scheduler = new BedrockThroughputScheduler<String>();
        scheduler.beginTick(0, 6, 0, true);
        scheduler.started("A");
        scheduler.beginTick(100, 6, 0, true);
        assertEquals(1, scheduler.availableStarts());
        scheduler.started("B");
        assertEquals(0, scheduler.availableStarts());
    }

    @Test void automaticCadenceLearnsCompletedWholeCycleDuration() {
        var scheduler = new BedrockThroughputScheduler<String>();
        scheduler.beginTick(0, 6, 0, true);
        scheduler.started("A");
        for (int tick = 1; tick <= 18; tick++) scheduler.beginTick(tick, 6, 0, true);
        scheduler.observeCycles(owner -> BedrockThroughputScheduler.CycleState.COMPLETE);
        scheduler.started("B");
        scheduler.beginTick(19, 6, 0, true);
        assertEquals(0, scheduler.availableStarts());
        scheduler.beginTick(20, 6, 0, true);
        assertEquals(0, scheduler.availableStarts());
        scheduler.beginTick(21, 6, 0, true);
        assertEquals(1, scheduler.availableStarts(), "18 ticks / six complete-cycle slots = three ticks");
    }

    @Test void fastCyclesCanUseFractionalCadenceWithoutAHardOnePerTickLimit() {
        var scheduler = new BedrockThroughputScheduler<String>();
        scheduler.beginTick(0, 6, 0, true);
        scheduler.started("A");
        scheduler.beginTick(1, 6, 0, true);
        scheduler.beginTick(2, 6, 0, true);
        scheduler.observeCycles(owner -> BedrockThroughputScheduler.CycleState.COMPLETE);
        assertEquals(3, scheduler.availableStarts());
        scheduler.started("B");
        scheduler.started("C");
        scheduler.started("D");
        assertEquals(0, scheduler.availableStarts());
    }

    @Test void cancelledAndPausedCyclesNeverTeachTheAutomaticCadenceToWaitLonger() {
        for (var state : new BedrockThroughputScheduler.CycleState[]{
                BedrockThroughputScheduler.CycleState.DISCARDED,
                BedrockThroughputScheduler.CycleState.UNMEASURED}) {
            var scheduler = new BedrockThroughputScheduler<String>();
            scheduler.beginTick(0, 6, 0, true);
            scheduler.started("A");
            scheduler.beginTick(1, 6, 0, true);
            scheduler.observeCycles(owner -> state);
            scheduler.beginTick(100, 6, 0, true);
            scheduler.observeCycles(owner -> BedrockThroughputScheduler.CycleState.COMPLETE);
            scheduler.started("B");
            scheduler.beginTick(101, 6, 0, true);
            assertEquals(1, scheduler.availableStarts());
        }
    }

    @Test void supplyAndInventoryPausesExcludeExistingAndNewCycleSamples() {
        var scheduler = new BedrockThroughputScheduler<String>();
        scheduler.beginTick(0, 6, 0, true);
        scheduler.started("A");
        scheduler.beginTick(1, 6, 0, false);
        scheduler.started("B");
        for (int tick = 2; tick <= 100; tick++) scheduler.beginTick(tick, 6, 0, true);
        scheduler.observeCycles(owner -> BedrockThroughputScheduler.CycleState.COMPLETE);
        scheduler.started("C");
        scheduler.beginTick(101, 6, 0, true);
        assertEquals(1, scheduler.availableStarts());
    }

    @Test void aCapacityChangeDoesNotCreateAnotherStartOnTheSameTick() {
        var scheduler = new BedrockThroughputScheduler<String>();
        scheduler.beginTick(0, 6, 2, true);
        scheduler.started("A");
        scheduler.beginTick(0, 8, 2, true);
        assertEquals(0, scheduler.availableStarts());
        scheduler.beginTick(1, 8, 4, true);
        assertEquals(0, scheduler.availableStarts());
        scheduler.beginTick(4, 8, 4, true);
        assertEquals(1, scheduler.availableStarts());
    }

    @Test void loweringTheCapDrainsStartedOwnersAndDoesNotDropTheirTiming() {
        var scheduler = new BedrockThroughputScheduler<String>();
        scheduler.beginTick(0, 6, 1, true);
        scheduler.started("A");
        scheduler.beginTick(1, 6, 1, true);
        scheduler.started("B");
        scheduler.beginTick(2, 1, 1, true);
        assertTrue(scheduler.canStart("A"));
        assertTrue(scheduler.canStart("B"));
        assertFalse(scheduler.canStart("C"));
        scheduler.observeCycles(owner -> owner.equals("A")
                ? BedrockThroughputScheduler.CycleState.COMPLETE : BedrockThroughputScheduler.CycleState.RUNNING);
        assertFalse(scheduler.canStart("C"));
        scheduler.observeCycles(owner -> BedrockThroughputScheduler.CycleState.COMPLETE);
        assertTrue(scheduler.canStart("C"));
    }

    @Test void resetClosesTheStartupGateAndDropsThePreviousWorldsSamples() {
        var scheduler = new BedrockThroughputScheduler<String>();
        scheduler.beginTick(0, 6, 2, true);
        scheduler.started("A");
        scheduler.reset();
        assertFalse(scheduler.canStart("A"));
        scheduler.beginTick(500, 6, 0, true);
        assertEquals(1, scheduler.availableStarts());
    }
}
