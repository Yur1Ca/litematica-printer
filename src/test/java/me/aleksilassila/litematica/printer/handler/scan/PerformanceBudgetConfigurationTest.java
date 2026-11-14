package me.aleksilassila.litematica.printer.handler.scan;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PerformanceBudgetConfigurationTest {
    private int previous;

    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    @BeforeEach void save() { this.previous = Configs.Core.WORK_PER_TICK.getIntegerValue(); }
    @AfterEach void restore() { Configs.Core.WORK_PER_TICK.setIntegerValue(this.previous); }

    @Test void workloadScalesTimeSafeguardsWithoutCombiningTheTwoPools() {
        Configs.Core.WORK_PER_TICK.setIntegerValue(4096);
        assertEquals(1_000_000L, ScanWorkSlice.configuredTimeLimitNanos());
        AtomicLong clock = new AtomicLong();
        ScanBudget budget = new ScanBudget(clock::get);
        budget.beginTick(1);
        clock.set(1_500_000L);
        budget.record("mine", new ScanMetricsAccumulator(), 0L);
        assertTrue(budget.isExceeded("print", clock.get()));
        var iteration = budget.openSlice("print", new ScanMetricsAccumulator());
        clock.addAndGet(750_000L);
        assertFalse(iteration.isExceeded(0L));
        clock.addAndGet(250_000L);
        assertTrue(iteration.isExceeded(0L));
        iteration.finish(0L);
        assertTrue(budget.openSlice("fill", new ScanMetricsAccumulator()).isExceeded(0L));

        Configs.Core.WORK_PER_TICK.setIntegerValue(16384);
        assertEquals(4_000_000L, ScanWorkSlice.configuredTimeLimitNanos());
        assertFalse(budget.isExceeded("print", clock.get()));
        assertFalse(budget.openSlice("fill", new ScanMetricsAccumulator()).isExceeded(0L));
    }

    @Test void configuredStepAndCandidateLimitsAreSeparateSharedAndResetOnlyNextTick() {
        Configs.Core.WORK_PER_TICK.setIntegerValue(256);
        assertEquals(1_000_000L, ScanWorkSlice.configuredTimeLimitNanos(), "Low workloads still allow cold setup to finish");
        ScanBudget budget = new ScanBudget(() -> 0L);
        budget.beginTick(1);
        for (int i = 0; i < 256; i++) assertTrue(budget.tryAdvance());
        assertFalse(budget.tryAdvance());
        var iteration = budget.openSlice("print", new ScanMetricsAccumulator());
        for (int i = 0; i < 256; i++) assertTrue(iteration.tryAdvance());
        assertFalse(iteration.tryAdvance());
        assertFalse(budget.openSlice("mine", new ScanMetricsAccumulator()).tryAdvance());
        budget.beginTick(1);
        assertFalse(budget.tryAdvance());
        budget.beginTick(2);
        assertTrue(budget.tryAdvance());
        assertTrue(budget.openSlice("mine", new ScanMetricsAccumulator()).tryAdvance());
    }

    @Test void changingWorkloadDoesNotChangeTheAutomaticBoundedFactCache() throws Exception {
        Configs.Core.WORK_PER_TICK.setIntegerValue(256);
        ScanCache cache = new ScanCache();
        cache.beginTick(null, null, 1L, RuntimeEpoch.INITIAL);
        Field field = ScanCache.class.getDeclaredField("snapshots");
        field.setAccessible(true);
        SectionSnapshotStore store = (SectionSnapshotStore) field.get(cache);
        var observation = new Observation();
        for (int section = 0; section < 5000; section++) {
            store.classify(new BlockPos(section * 16, 0, 0), ScanIntent.MINE, false, observation);
        }
        int retained = store.retainedBytes();
        assertTrue(retained <= 16 * 1024 * 1024);
        assertTrue(store.retainedSectionCount() <= 4096);
        Configs.Core.WORK_PER_TICK.setIntegerValue(131072);
        cache.beginTick(null, null, 2L, RuntimeEpoch.INITIAL);
        assertEquals(retained, store.retainedBytes());
        BlockPos evicted = new BlockPos(16, 0, 0);
        assertFalse(store.isKnownZero(evicted, ScanIntent.MINE, false));
        assertTrue(store.hasPotentialOrUnknown(ScanGeometry.sectionKey(1, 0, 0), ScanIntent.MINE, false));
        observation.flags = 1;
        assertEquals(1, store.classify(evicted, ScanIntent.MINE, false, observation));
        for (int section = 5000; section < 8000; section++) {
            store.classify(new BlockPos(section * 16, 0, 0), ScanIntent.MINE, false, observation);
        }
        assertTrue(store.retainedBytes() <= 16 * 1024 * 1024);
        assertTrue(store.retainedSectionCount() <= 4096);
        cache.clear();
    }

    private static final class Observation implements WorldObservationPort {
        byte flags;
        @Override public boolean hasChunk(int x, int z) { return true; }
        @Override public byte classify(ScanIntent intent, BlockPos pos, boolean extra) { return this.flags; }
        @Override public BlockState worldState(BlockPos pos) { throw new AssertionError(); }
        @Override public BlockState schematicState(BlockPos pos) { throw new AssertionError(); }
        @Override public boolean hasFillSupport(BlockPos pos) { throw new AssertionError(); }
    }
}
