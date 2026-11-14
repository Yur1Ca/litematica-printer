//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.scan;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.lang.reflect.Field;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import me.aleksilassila.litematica.printer.config.Configs;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThirdReviewWorkLimitTest {
    private static final int EXPECTED_LIMIT = 8192;
    private int oldScanLimit;

    @BeforeEach void pinScanLimit() {
        this.oldScanLimit = Configs.Core.WORK_PER_TICK.getIntegerValue();
        Configs.Core.WORK_PER_TICK.setIntegerValue(EXPECTED_LIMIT);
    }

    @AfterEach void restoreScanLimit() {
        Configs.Core.WORK_PER_TICK.setIntegerValue(this.oldScanLimit);
    }

    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void lowWorkloadStillProgressesAfterTimedColdSetupAndResumesNextTick() throws Exception {
        Configs.Core.WORK_PER_TICK.setIntegerValue(256);
        try (var f = new ScanIntegrationFixture(0)) {
            f.level.allChunksLoaded = true;
            AtomicLong clock = new AtomicLong();
            Field budgetField = ScanCache.class.getDeclaredField("budget");
            budgetField.setAccessible(true);
            Object budget = budgetField.get(f.cache);
            Field clockField = budget.getClass().getDeclaredField("nanoClock");
            clockField.setAccessible(true);
            clockField.set(budget, (LongSupplier) () -> clock.getAndAdd(100_000L));
            List<PrinterBox> scope = List.of(new PrinterBox(0, 0, 0, 1023, 0, 0));
            f.tick(1);
            Iterable<BlockPos> first = f.scan("custom", scope, 0, ScanIntent.CUSTOM);
            assertTrue(f.cache.metricsFor("custom").scanNanos() > 0, "Real source setup is charged before discovery");
            var iterator = first.iterator();
            assertTrue(iterator.hasNext(), "A 256-position workload must not starve behind a 62.5 microsecond guard");
            BlockPos offered = iterator.next();
            f.tick(2);
            var continued = f.scan("custom", scope, 0, ScanIntent.CUSTOM).iterator();
            assertTrue(continued.hasNext());
            assertNotEquals(offered, continued.next(), "The next tick continues its saved cursor");
        }
    }

    @Test void aFrozenClockStillBoundsRejectedGeometryAndResumesWithoutLosingPositions() throws Exception {
        try (var f = new ScanIntegrationFixture(0)) {
            Set<BlockPos> inspected = new HashSet<>();
            AtomicInteger visits = new AtomicInteger();
            List<PrinterBox> scope = List.of(new PrinterBox(0, 0, 0, 19999, 0, 0));
            for (int tick = 1; tick <= 3; tick++) {
                f.tick(tick);
                int before = visits.get();
                Iterable<BlockPos> source = f.cache.iterable("mine", scope, f.level, f.schematic,
                        null, 0, ScanIntent.MINE, pos -> true, pos -> {
                            visits.incrementAndGet();
                            assertTrue(inspected.add(pos.immutable()), "A paused cursor must not repeat a rejected position");
                            return false;
                        });
                assertFalse(source.iterator().hasNext());
                assertEquals(tick < 3 ? EXPECTED_LIMIT : 20000 - 2 * EXPECTED_LIMIT, visits.get() - before);
                assertEquals(tick < 3 ? ScanAvailability.PAUSED : ScanAvailability.COMPLETE,
                        ((ScanCandidateIterable) source).availability());
            }
            assertEquals(20000, inspected.size());
        }
    }

    @Test void differentOwnersShareTheSameFrozenClockWorkLimit() throws Exception {
        try (var f = new ScanIntegrationFixture(0)) {
            AtomicInteger visits = new AtomicInteger();
            f.tick(1);
            for (String owner : List.of("mine", "fill")) {
                int length = owner.equals("mine") ? 4096 : 16384;
                Iterable<BlockPos> source = f.cache.iterable(owner,
                        List.of(new PrinterBox(0, 0, 0, length - 1, 0, 0)), f.level, f.schematic,
                        null, 0, ScanIntent.MINE, pos -> true, pos -> {
                            visits.incrementAndGet();
                            return false;
                        });
                assertFalse(source.iterator().hasNext());
            }
            assertEquals(EXPECTED_LIMIT, visits.get(), "Another owner must not get a second work quota");
        }
    }

    @Test void acceptedCandidateOutputAlsoHasAFixedPerTickBoundAndResumes() throws Exception {
        try (var f = new ScanIntegrationFixture(0)) {
            f.level.allChunksLoaded = true;
            Set<BlockPos> result = new HashSet<>();
            List<PrinterBox> scope = List.of(new PrinterBox(0, 0, 0, 9999, 0, 0));
            for (int tick = 1; tick <= 2; tick++) {
                f.tick(tick);
                int before = result.size();
                for (BlockPos pos : f.scan("custom", scope, 0, ScanIntent.CUSTOM)) {
                    assertTrue(result.add(pos), "Quota exhaustion must not rewind or lose the source cursor");
                }
                assertEquals(tick == 1 ? EXPECTED_LIMIT : 10000 - EXPECTED_LIMIT, result.size() - before);
            }
            assertEquals(10000, result.size());
        }
    }
}
//#endif
