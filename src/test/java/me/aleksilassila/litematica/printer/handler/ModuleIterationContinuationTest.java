package me.aleksilassila.litematica.printer.handler;

import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintPlacementResult;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintTargetScheduler;
import me.aleksilassila.litematica.printer.handler.handlers.print.SortedSchematicTargetQueue;
import me.aleksilassila.litematica.printer.handler.scan.ScanAvailability;
import me.aleksilassila.litematica.printer.handler.scan.ScanCache;
import me.aleksilassila.litematica.printer.handler.scan.ScanCandidateIterable;
import me.aleksilassila.litematica.printer.handler.scan.ScanEngine;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.printer.action.ActionBroker;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the real iteration loop and destructive sorted queue without a running client. */
class ModuleIterationContinuationTest {
    private static final BlockPos FIRST = new BlockPos(1, 2, 3);
    private static final BlockPos SECOND = new BlockPos(2, 2, 3);
    private static final PrinterBox BOX = new PrinterBox(0, 0, 0, 8, 8, 8);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void lookOwnerStopsBeforeTheCandidateSourceIsProbedOrConsumed() throws Exception {
        try (var fixture = new Fixture(FIRST)) {
            assertTrue(fixture.broker.tryAcquire("other", EnumSet.of(ResourceLease.LOOK), 0L));

            var outcome = fixture.run();

            assertTrue(outcome.interrupt());
            assertEquals(0, fixture.module.source.probes, "hasNext may advance a live scan cursor");
            assertEquals(0, fixture.module.source.fetches);
            assertTrue(fixture.module.inspected.isEmpty());
            assertTrue(fixture.module.targets.hasRunnableWork());

            fixture.broker.releaseOwner("other");
            fixture.run();
            assertEquals(List.of(FIRST), fixture.module.executed);
        }
    }

    @Test
    void guardLimitStopsBeforeProbingOrRemovingTheNextCandidate() throws Exception {
        try (var fixture = new Fixture(FIRST, SECOND)) {
            fixture.module.guardLimit = 1;

            var outcome = fixture.run();

            assertTrue(outcome.interrupt());
            assertEquals(List.of(FIRST), fixture.module.executed);
            assertEquals(1, fixture.module.source.probes);
            assertEquals(1, fixture.module.source.fetches);
            assertTrue(fixture.module.targets.hasRunnableWork());

            fixture.module.guardLimit = 0;
            fixture.run();
            assertEquals(List.of(FIRST, SECOND), fixture.module.executed);
        }
    }

    @Test
    void cooldownRetainsTheRemovedTargetUntilItBecomesEligible() throws Exception {
        try (var fixture = new Fixture(FIRST)) {
            fixture.module.cooling.add(FIRST);

            fixture.run();

            assertEquals(List.of(FIRST), fixture.module.deferred);
            assertTrue(fixture.module.executed.isEmpty());
            assertTrue(fixture.module.targets.hasRunnableWork(), "A timer expiry does not dirty the scan source");

            fixture.module.cooling.clear();
            fixture.run();
            assertEquals(List.of(FIRST), fixture.module.executed);
            assertFalse(fixture.module.targets.hasRunnableWork());
        }
    }

    @Test
    void mainHandOwnerAllowsInvalidCandidatesToAdvanceAndRetainsTheEligibleTarget() throws Exception {
        try (var fixture = new Fixture(FIRST, SECOND)) {
            fixture.module.invalid.add(FIRST);
            assertTrue(fixture.broker.tryAcquire("other", EnumSet.of(ResourceLease.MAIN_HAND), 0L));

            var outcome = fixture.run();

            assertTrue(outcome.interrupt());
            assertEquals(List.of(FIRST, SECOND), fixture.module.inspected,
                    "MAIN_HAND must not stop a candidate that needs no action");
            assertEquals(List.of(SECOND), fixture.module.deferred);
            assertTrue(fixture.module.executed.isEmpty());
            assertTrue(fixture.module.targets.hasRunnableWork());

            fixture.broker.releaseOwner("other");
            fixture.run();
            assertEquals(List.of(SECOND), fixture.module.executed);
            assertFalse(fixture.module.targets.hasRunnableWork());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ScanCache cache = new ScanCache();
        final ActionBroker broker = new ActionBroker(() -> RuntimeEpoch.INITIAL, () -> null, null);
        final TestModule module;

        Fixture(BlockPos... positions) throws Exception {
            this.module = allocate(TestModule.class);
            set(this.module, "id", "print");
            set(this.module, "timing", RuntimeTiming.disabled());
            set(this.module, "scanEngine", new ScanEngine(() -> RuntimeEpoch.INITIAL, () -> null, this.cache));
            set(this.module, "actionBroker", this.broker);
            set(this.module, "guiBlockInfoBuffer", new GuiBlockInfoBuffer());
            SortedSchematicTargetQueue queue = new SortedSchematicTargetQueue(null);
            this.module.targets = new PrintTargetScheduler(queue, () -> {});
            this.module.targets.setSortedMode(true);
            this.module.source = new CountingSource(queue);
            this.module.cooling = new HashSet<>();
            this.module.invalid = new HashSet<>();
            this.module.inspected = new ArrayList<>();
            this.module.deferred = new ArrayList<>();
            this.module.executed = new ArrayList<>();
            for (BlockPos pos : positions) queue.requeue(pos);
        }

        FeatureModuleBase.IterationOutcome run() {
            return this.module.runIteration(BOX);
        }

        @Override
        public void close() {
            this.cache.clear();
        }
    }

    private static final class TestModule extends FeatureModuleBase {
        PrintTargetScheduler targets;
        CountingSource source;
        Set<BlockPos> cooling;
        Set<BlockPos> invalid;
        List<BlockPos> inspected;
        List<BlockPos> deferred;
        List<BlockPos> executed;
        int guardLimit;

        private TestModule() {
            super(null, "print", null, null, null, false);
            throw new AssertionError("Allocated without a Minecraft client");
        }

        @Override protected int getScanGuardLimit() { return this.guardLimit; }
        @Override protected int getMaxEffectiveExecutionsPerTick() { return 0; }
        @Override protected boolean iterationPositionsPrefilterReachAndSelection() { return true; }
        @Override protected Iterable<BlockPos> getIterationPositions(PrinterBox box) {
            return ScanCandidateIterable.prepend(this.targets.retainedTargets(), this.source);
        }
        @Override public boolean isBlockPosOnCooldown(BlockPos pos) { return this.cooling.contains(pos); }

        @Override
        public boolean canIterationBlockPos(BlockPos pos) {
            this.inspected.add(pos);
            return !this.invalid.contains(pos);
        }

        @Override
        protected void onIterationDeferred(BlockPos pos) {
            this.deferred.add(pos);
            this.targets.onResult(pos, PrintPlacementResult.deferred(false), false);
        }

        @Override
        protected void executeIteration(BlockPos pos, AtomicReference<Boolean> skip) {
            this.executed.add(pos);
            this.targets.onResult(pos, new PrintPlacementResult(
                    true, false, PrintPlacementResult.TaskEvent.SUCCESS, -1), false);
        }
    }

    private static final class CountingSource implements ScanCandidateIterable {
        private final SortedSchematicTargetQueue queue;
        int probes;
        int fetches;

        CountingSource(SortedSchematicTargetQueue queue) { this.queue = queue; }

        @Override public ScanAvailability availability() { return this.queue.availability(); }
        @Override public boolean isBuffered() { return true; }

        @Override
        public Iterator<BlockPos> iterator() {
            Iterator<BlockPos> delegate = this.queue.iterator();
            return new Iterator<>() {
                @Override public boolean hasNext() {
                    probes++;
                    return delegate.hasNext();
                }

                @Override public BlockPos next() {
                    fetches++;
                    return delegate.next();
                }
            };
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = FeatureModuleBase.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }
}
