package me.aleksilassila.litematica.printer.handler.scan;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanBudgetTest {
    @Test
    void exhaustedDiscoveryTimeDoesNotSpendIterationTime() {
        var now = new AtomicLong();
        var budget = new ScanBudget(now::get, () -> 2_000_000L);
        budget.beginTick(1);
        var slice = budget.openSlice("print", new ScanMetricsAccumulator());
        now.set(20_000_000L);
        budget.record("print", new ScanMetricsAccumulator(), 0L);
        assertTrue(budget.isExceeded("mine", now.get()));
        assertFalse(slice.isExceeded(0L), "Nested discovery is excluded from iteration's independent allowance");
        slice.finish(0L);
        assertFalse(budget.openSlice("mine", new ScanMetricsAccumulator()).isExceeded(0L));
    }

    @Test
    void exhaustedDiscoveryStepsDoNotSpendBufferedCandidateQuota() {
        var budget = new ScanBudget(() -> 0L, () -> 2_000_000L);
        budget.beginTick(1);
        while (budget.tryAdvance()) { }
        assertTrue(budget.isExceeded("mine", 0L));
        var iteration = budget.openSlice("print", new ScanMetricsAccumulator());
        assertTrue(iteration.tryAdvance(), "A ready candidate uses a separate planning quota");
        assertFalse(iteration.isExceeded(0L));
    }

    @Test
    void oneOwnersRecordedWorkConsumesTheSharedScanBudget() {
        AtomicLong now = new AtomicLong();
        long configuredNanos = 2_000_000L;
        ScanBudget budget = new ScanBudget(now::get, () -> configuredNanos);
        ScanMetricsAccumulator metrics = new ScanMetricsAccumulator();

        budget.beginTick(1L);
        now.set(configuredNanos);
        assertTrue(budget.isExceeded("mine", 0L));
        budget.record("mine", metrics, 0L);
        assertTrue(budget.isExceeded("fluid", now.get()));
        assertTrue(budget.isExceeded("print", now.get()));
        assertEquals(configuredNanos, metrics.scanNanos);
    }

    @Test
    void budgetResetsAtTheStartOfTheNextTick() {
        AtomicLong now = new AtomicLong();
        long configuredNanos = 2_000_000L;
        ScanBudget budget = new ScanBudget(now::get, () -> configuredNanos);

        budget.beginTick(1L);
        now.set(configuredNanos);
        assertTrue(budget.isExceeded("print", 0L));
        budget.record("print", new ScanMetricsAccumulator(), 0L);
        budget.beginTick(1L);
        assertTrue(budget.isExceeded("mine", now.get()), "Repeated beginTick cannot grant another budget");

        budget.beginTick(2L);
        assertFalse(budget.isExceeded("print", now.get()));
        assertFalse(budget.isExceeded("mine", now.get()));
    }

    @Test
    void repeatedCandidateLookupsAccumulateAcrossTheWholeTick() {
        AtomicLong now = new AtomicLong();
        long configuredNanos = 2_000_000L;
        ScanBudget budget = new ScanBudget(now::get, () -> configuredNanos);
        ScanMetricsAccumulator metrics = new ScanMetricsAccumulator();

        budget.beginTick(1L);
        now.set(800_000L);
        budget.record("print", metrics, 0L);

        long secondLookupStart = now.get();
        now.set(1_600_000L);
        budget.record("print", metrics, secondLookupStart);

        long thirdLookupStart = now.get();
        now.set(2_050_000L);
        assertTrue(budget.isExceeded("print", thirdLookupStart));
        assertTrue(budget.isExceeded("mine", thirdLookupStart));
        assertEquals(1_600_000L, metrics.scanNanos, "In-progress work is charged when the lookup records it");
        budget.record("print", metrics, thirdLookupStart);
        assertEquals(2_050_000L, metrics.scanNanos);
        assertTrue(budget.isExceeded("fluid", now.get()));
    }

    @Test
    void differentOwnersAddUpToTheBudgetWhileKeepingSeparateMetrics() {
        var now = new AtomicLong();
        var budget = new ScanBudget(now::get, () -> 2_000_000L);
        var print = new ScanMetricsAccumulator();
        var mine = new ScanMetricsAccumulator();
        var fluid = new ScanMetricsAccumulator();
        budget.beginTick(10);
        now.set(700_000L);
        budget.record("print", print, 0L);
        now.set(1_500_000L);
        budget.record("mine", mine, 700_000L);
        assertFalse(budget.isExceeded("fluid", now.get()));
        now.set(2_000_000L);
        assertTrue(budget.isExceeded("fluid", 1_500_000L));
        budget.record("fluid", fluid, 1_500_000L);
        assertTrue(budget.isExceeded("bedrock", now.get()));
        assertEquals(700_000L, print.scanNanos);
        assertEquals(800_000L, mine.scanNanos);
        assertEquals(500_000L, fluid.scanNanos);
    }

    @Test
    void resettingTheBudgetAllowsANewLifetimeAtTheSameGameTick() {
        var now = new AtomicLong();
        var budget = new ScanBudget(now::get, () -> 2_000_000L);
        budget.beginTick(10);
        now.set(2_000_000L);
        budget.record("print", new ScanMetricsAccumulator(), 0L);
        budget.reset();
        budget.beginTick(10);
        assertFalse(budget.isExceeded("print", now.get()));
        assertFalse(budget.isExceeded("mine", now.get()));
    }

    @Test
    void rotatingTheFirstOwnerAllowsEachOwnerToProgressAfterTheNextTickReset() {
        var now = new AtomicLong();
        var budget = new ScanBudget(now::get, () -> 2_000_000L);
        var print = new ScanMetricsAccumulator();
        var mine = new ScanMetricsAccumulator();
        budget.beginTick(10);
        now.set(2_000_000L);
        budget.record("print", print, 0L);
        assertTrue(budget.isExceeded("mine", now.get()));

        budget.beginTick(11);
        long mineStart = now.get();
        assertFalse(budget.isExceeded("mine", mineStart));
        now.set(4_000_000L);
        budget.record("mine", mine, mineStart);
        assertTrue(budget.isExceeded("print", now.get()));
        assertEquals(2_000_000L, print.scanNanos);
        assertEquals(2_000_000L, mine.scanNanos);
    }

    @Test
    void setupAndLookupStartTimesUseTheInjectedClockForTheSameBudget() {
        var now = new AtomicLong(50_000_000L);
        var budget = new ScanBudget(now::get, () -> 2_000_000L);
        var print = new ScanMetricsAccumulator();
        budget.beginTick(10);
        long setupStart = budget.start();
        now.addAndGet(750_000L);
        budget.record("print", print, setupStart);
        long lookupStart = budget.start();
        now.addAndGet(1_250_000L);
        assertTrue(budget.isExceeded("mine", lookupStart));
        budget.record("print", print, lookupStart);
        assertEquals(2_000_000L, print.scanNanos);
        assertTrue(budget.isExceeded("fluid", budget.start()));
    }

    @Test
    void workSliceDoesNotChargeRecordedSetupAndLookupTwice() {
        var now = new AtomicLong();
        var budget = new ScanBudget(now::get, () -> 1_000_000L);
        var previousOwner = new ScanMetricsAccumulator();
        var print = new ScanMetricsAccumulator();
        budget.beginTick(10);
        now.set(300_000L);
        budget.record("mine", previousOwner, 0L);
        var slice = budget.openSlice("print", print);
        now.set(400_000L);
        budget.record("print", print, 300_000L); // Setup inside the outer slice.
        now.set(600_000L);
        budget.record("print", print, 400_000L); // Lookup inside the same slice.
        now.set(800_000L); // Another 0.2 ms of outer planning was not recorded separately.
        assertFalse(slice.isExceeded(0L), "The 0.3 ms of nested records is part of this slice's 0.5 ms");
        slice.finish(0L);
        assertEquals(500_000L, print.scanNanos);
        assertEquals(300_000L, previousOwner.scanNanos);
        assertFalse(budget.isExceeded("fluid", now.get()));
        long nextLookup = budget.start();
        now.set(1_000_000L);
        assertFalse(budget.isExceeded("fluid", nextLookup), "Iteration planning does not reduce discovery's remaining 0.4 ms");
        now.set(1_200_000L);
        assertTrue(budget.isExceeded("fluid", nextLookup));
    }

    @Test
    void outerPlanningAloneExhaustsTheSharedBudgetForTheNextOwner() {
        var now = new AtomicLong(50_000_000L);
        var budget = new ScanBudget(now::get, () -> 2_000_000L);
        var print = new ScanMetricsAccumulator();
        budget.beginTick(10);
        var slice = budget.openSlice("print", print);
        now.addAndGet(2_000_000L); // Cached target sorting/planning without a live scan lookup.
        assertTrue(slice.isExceeded(0L));
        slice.finish(0L);
        assertEquals(2_000_000L, print.scanNanos);
        var mine = budget.openSlice("mine", new ScanMetricsAccumulator());
        assertTrue(mine.isExceeded(0L), "Creating another owner slice must not grant a fresh budget");
        mine.finish(0L);
    }

    @Test
    void workSliceExcludesActionTimeButRetainsSetupAndPlanning() {
        var now = new AtomicLong();
        var budget = new ScanBudget(now::get, () -> 2_000_000L);
        var print = new ScanMetricsAccumulator();
        budget.beginTick(10);
        var slice = budget.openSlice("print", print);
        now.set(400_000L);
        budget.record("print", print, 0L);
        now.set(5_400_000L); // Five milliseconds inside the actual action executor.
        assertFalse(slice.isExceeded(5_000_000L));
        now.set(5_700_000L); // Another 0.3 ms of planning after the action.
        slice.finish(5_000_000L);
        assertEquals(700_000L, print.scanNanos);
        long nextLookup = budget.start();
        now.set(7_299_999L);
        assertFalse(budget.isExceeded("mine", nextLookup));
        now.incrementAndGet();
        assertTrue(budget.isExceeded("mine", nextLookup));
    }

    @Test
    void finishingAWorkSliceTwiceDoesNotChargeTimeAfterItsFirstFinish() {
        var now = new AtomicLong();
        var budget = new ScanBudget(now::get, () -> 2_000_000L);
        var print = new ScanMetricsAccumulator();
        budget.beginTick(10);
        var slice = budget.openSlice("print", print);
        now.set(900_000L);
        slice.finish(0L);
        now.set(3_000_000L);
        slice.finish(0L);
        assertEquals(900_000L, print.scanNanos);
        assertFalse(budget.isExceeded("mine", now.get()));
    }

    @Test
    void excludedTimeCannotCreateCreditAgainstAnEarlierOwnersWork() {
        var now = new AtomicLong();
        var budget = new ScanBudget(now::get, () -> 2_000_000L);
        budget.beginTick(10);
        now.set(1_000_000L);
        budget.record("mine", new ScanMetricsAccumulator(), 0L);
        var print = new ScanMetricsAccumulator();
        var slice = budget.openSlice("print", print);
        now.set(1_100_000L);
        assertFalse(slice.isExceeded(5_000_000L));
        slice.finish(5_000_000L);
        assertEquals(0L, print.scanNanos);
        long nextLookup = budget.start();
        now.set(2_100_000L);
        assertTrue(budget.isExceeded("fluid", nextLookup));
    }
}
