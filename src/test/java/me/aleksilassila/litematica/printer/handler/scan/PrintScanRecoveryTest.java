//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.scan;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.guide.Guides;
import me.aleksilassila.litematica.printer.handler.FeatureModuleBase;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.handler.handlers.PrintHandler;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintPlacementResult;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintTask;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintTaskAction;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintTaskBuildResult;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintTargetScheduler;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintWorkflowScheduler;
import me.aleksilassila.litematica.printer.handler.handlers.print.SortedSchematicTargetQueue;
import me.aleksilassila.litematica.printer.integration.litematica.LitematicaAdapter;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.action.ActionBroker;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Real PRINT inspection, queues and confirmation state; no game/network process. */
class PrintScanRecoveryTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void pendingTargetSurvivesInspectionAndLeavesRetryQueueAfterConfirmation(boolean sorted) throws Exception {
        try (var f = new Fixture(sorted)) {
            f.stats.trackExpectedBlockState(HudStatsManager.Mode.PRINT, BlockPos.ZERO,
                    Blocks.COBBLESTONE.defaultBlockState());

            assertFalse(f.handler.canIterationBlockPos(BlockPos.ZERO));
            assertTrue(f.targets.hasRunnableWork(),
                    "A short confirmation wait must not lose a target from an exhausted source");
            assertEquals(List.of(BlockPos.ZERO), f.targets.retainedTargets());
            assertFalse(f.queue.iterator().hasNext(), "A retained retry must not occupy the fresh sorted queue");

            f.stats.confirmBlockUpdate(BlockPos.ZERO, Blocks.COBBLESTONE.defaultBlockState());
            f.world.level.world.put(BlockPos.ZERO, Blocks.COBBLESTONE.defaultBlockState());
            assertFalse(f.handler.canIterationBlockPos(BlockPos.ZERO));
            assertFalse(f.targets.hasRunnableWork(),
                    "A matching world state must retire the retained target instead of keeping FULL busy forever");
            assertTrue(f.targets.retainedTargets().isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void recentBreakWaitRetainsOnlyTheAffectedTarget(boolean sorted) throws Exception {
        try (var f = new Fixture(sorted)) {
            f.interactions.markRecentlyBroken(BlockPos.ZERO);

            assertFalse(f.handler.canIterationBlockPos(BlockPos.ZERO));
            assertTrue(f.targets.hasRunnableWork(), "A recent-break marker is a transient wait");
            assertEquals(List.of(BlockPos.ZERO), f.targets.retainedTargets());
            assertFalse(f.queue.iterator().hasNext(), "A retained retry must not occupy the fresh sorted queue");
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void resolvedRetryIsDiscardedWhenItsTargetAlreadyMatches(boolean sorted) throws Exception {
        try (var f = new Fixture(sorted)) {
            f.targets.onResult(BlockPos.ZERO, PrintPlacementResult.deferred(false), false);
            assertEquals(List.of(BlockPos.ZERO), f.targets.retainedTargets());
            assertFalse(f.queue.iterator().hasNext(), "A retained retry must not occupy the fresh sorted queue");
            f.world.level.world.put(BlockPos.ZERO, Blocks.COBBLESTONE.defaultBlockState());

            assertFalse(f.handler.canIterationBlockPos(BlockPos.ZERO));

            assertFalse(f.targets.hasRunnableWork(),
                    "An obsolete retry cannot keep the scanner awake after another action resolved it");
            assertTrue(f.targets.retainedTargets().isEmpty());
        }
    }

    @Test void retryOutsideExactReachDoesNotKeepTheScannerRunnable() throws Exception {
        try (var f = new Fixture(false)) {
            f.targets.onResult(BlockPos.ZERO, PrintPlacementResult.deferred(false), false);
            f.handler.unreachable = true;

            assertTrue(ScanIntegrationFixture.drain(f.handler.positions()).isEmpty());

            assertFalse(f.targets.hasRunnableWork(),
                    "A target inside the coarse box but outside exact reach must leave the active retry queue");
        }
    }

    @Test void readyWorkflowAndRetryPreserveBufferedSortedSourceWithoutRepeatingTheirTarget() throws Exception {
        try (var f = new Fixture(true)) {
            Configs.Print.PRINT_SORT_TARGETS.setBooleanValue(true);
            f.world.tick(10L);
            f.addReadyWorkflow(BlockPos.ZERO);
            f.targets.onResult(BlockPos.ZERO, PrintPlacementResult.deferred(false), false);

            Iterable<BlockPos> positions = f.handler.positions();
            ScanCandidateIterable source = assertInstanceOf(ScanCandidateIterable.class, positions,
                    "A ready workflow prefix must preserve the sorted queue's scan metadata");

            assertTrue(source.isBuffered(), "Collecting the sorted queue must not consume its execution budget");
            assertEquals(ScanAvailability.READY, source.availability());
            assertEquals(List.of(BlockPos.ZERO), ScanIntegrationFixture.drain(source),
                    "A shared workflow, retry and sorted target gets only one attempt per pass");
            assertEquals(ScanAvailability.COMPLETE, source.availability());
        }
    }

    @ParameterizedTest @EnumSource(value = ScanAvailability.class, names = {"PAUSED", "COMPLETE"})
    void retryPrefixPreservesTailAvailabilityWithoutProbingItEarly(ScanAvailability tailState) throws Exception {
        try (var f = new Fixture(false)) {
            f.targets.onResult(BlockPos.ZERO, PrintPlacementResult.deferred(false), false);
            var tail = new CountingScanSource(List.of(), false, tailState);
            f.handler.normalPositions = tail;

            ScanCandidateIterable source = assertInstanceOf(ScanCandidateIterable.class, f.handler.positions(),
                    "An ordinary retry prefix must preserve the live source's availability");
            assertFalse(source.isBuffered());
            assertEquals(ScanAvailability.READY, source.availability(), "The retained retry is available first");
            Iterator<BlockPos> iterator = source.iterator();
            assertTrue(iterator.hasNext());
            assertEquals(0, tail.iterators, "Creating the mixed iterator must not create its live tail early");
            assertEquals(0, tail.probes, "Checking a retained retry must not advance the live scan cursor");

            assertEquals(BlockPos.ZERO, iterator.next());
            assertEquals(tailState, source.availability(), "Once the prefix is consumed, expose the tail state");
            assertFalse(iterator.hasNext());
            assertEquals(tailState, source.availability());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void mixedRetryStreamRetainsTailBudgetSemanticsAndDeduplicatesEachPass(boolean buffered) throws Exception {
        try (var f = new Fixture(false)) {
            f.targets.onResult(BlockPos.ZERO, PrintPlacementResult.deferred(false), false);
            BlockPos next = BlockPos.ZERO.east();
            var tail = new CountingScanSource(List.of(BlockPos.ZERO, next, next), buffered, ScanAvailability.COMPLETE);
            f.handler.normalPositions = tail;

            ScanCandidateIterable source = assertInstanceOf(ScanCandidateIterable.class, f.handler.positions());

            assertEquals(buffered, source.isBuffered(), "Only a buffered tail may bypass the live scan budget");
            assertEquals(List.of(BlockPos.ZERO, next), ScanIntegrationFixture.drain(source),
                    "Retries and repeated tail positions must not receive a second attempt in the same pass");
            assertEquals(ScanAvailability.COMPLETE, source.availability());
            assertEquals(List.of(BlockPos.ZERO, next), ScanIntegrationFixture.drain(source),
                    "Deduplication belongs to one iterator rather than suppressing a later retry pass");
        }
    }

    @Test void mixedRetryStreamWithRawTailKeepsBudgetChecksAndDoesNotProbeTailBeforeTheRetry() throws Exception {
        try (var f = new Fixture(false)) {
            f.targets.onResult(BlockPos.ZERO, PrintPlacementResult.deferred(false), false);
            BlockPos next = BlockPos.ZERO.east();
            var tail = new CountingRawSource(List.of(BlockPos.ZERO, next, next));
            f.handler.normalPositions = tail;

            ScanCandidateIterable source = assertInstanceOf(ScanCandidateIterable.class, f.handler.positions());
            assertFalse(source.isBuffered(), "A raw iterable tail must remain subject to the scan budget");
            Iterator<BlockPos> iterator = source.iterator();
            assertTrue(iterator.hasNext());
            assertEquals(0, tail.iterators);
            assertEquals(0, tail.probes);
            assertEquals(BlockPos.ZERO, iterator.next());
            assertTrue(iterator.hasNext());
            assertEquals(next, iterator.next());
            assertFalse(iterator.hasNext());
            assertEquals(ScanAvailability.COMPLETE, source.availability());
        }
    }

    @Test void realIterationExecutesItsReadyTargetBeforeTheStopHookReplenishes() throws Exception {
        try (var f = new Fixture(true, 513)) {
            Configs.Print.PRINT_SORT_TARGETS.setBooleanValue(true);
            f.world.tick(10L);
            var initial = f.targets.sortedPositions(f.world.boxes, f.world.level,
                    f.world.schematic, f.world.player, 0).iterator();
            for (int index = 0; index < 241; index++) initial.next();
            f.world.tick(11L);
            f.handler.simpleExecution = true;
            f.handler.sourceBoxes = f.world.boxes;
            int[] executions = {0};
            f.handler.execute = pos -> {
                assertEquals(0, f.world.cache.metricsFor("print").acceptedTargets(),
                        "Ready execution must precede this tick's source production");
                executions[0]++;
                f.targets.onResult(pos, new PrintPlacementResult(true, false,
                        PrintPlacementResult.TaskEvent.SUCCESS, -1), false);
            };

            f.handler.runIteration(new PrinterBox(BlockPos.ZERO));

            assertEquals(1, executions[0], "The original per-tick execution limit still applies");
            assertEquals(16, f.world.cache.metricsFor("print").acceptedTargets(),
                    "The real runner must invoke PRINT's post-execution replenishment hook");
            assertEquals(30, ScanIntegrationFixture.drain(f.queue).size());
        }
    }

    private static final class TestPrintHandler extends PrintHandler {
        boolean unreachable;
        boolean simpleExecution;
        List<PrinterBox> sourceBoxes;
        Consumer<BlockPos> execute;
        Iterable<BlockPos> normalPositions;
        private TestPrintHandler() { super(null); }
        Iterable<BlockPos> positions() { return super.getIterationPositions(new PrinterBox(BlockPos.ZERO)); }
        @Override protected List<PrinterBox> getScanSourceBoxes(PrinterBox box) {
            return this.sourceBoxes == null ? List.of(box) : this.sourceBoxes;
        }
        @Override protected boolean canReachIterationPosition(BlockPos pos) { return !this.unreachable; }
        @Override protected boolean isInSelectionRange(BlockPos pos) { return true; }
        @Override protected boolean isSchematicBlockHandler() { return !this.simpleExecution; }
        @Override protected boolean iterationPositionsPrefilterReachAndSelection() { return this.simpleExecution; }
        @Override protected int getMaxEffectiveExecutionsPerTick() { return 1; }
        @Override public boolean isBlockPosOnCooldown(BlockPos pos) {
            return !this.simpleExecution && super.isBlockPosOnCooldown(pos);
        }
        @Override public boolean canIterationBlockPos(BlockPos pos) {
            return this.simpleExecution || super.canIterationBlockPos(pos);
        }
        @Override protected void executeIteration(BlockPos pos, AtomicReference<Boolean> skip) {
            this.execute.accept(pos);
        }
        @Override protected Iterable<BlockPos> getCachedFilteredIterationPositions(
                PrinterBox box, ScanIntent intent, Predicate<BlockPos> predicate) {
            return this.normalPositions == null ? List.of() : this.normalPositions;
        }
    }

    private static class CountingRawSource implements Iterable<BlockPos> {
        final List<BlockPos> positions;
        int iterators;
        int probes;

        CountingRawSource(List<BlockPos> positions) { this.positions = positions; }

        @Override public Iterator<BlockPos> iterator() {
            this.iterators++;
            Iterator<BlockPos> delegate = this.positions.iterator();
            return new Iterator<>() {
                @Override public boolean hasNext() {
                    probes++;
                    return delegate.hasNext();
                }

                @Override public BlockPos next() { return delegate.next(); }
            };
        }
    }

    private static final class CountingScanSource extends CountingRawSource implements ScanCandidateIterable {
        private final boolean buffered;
        private final ScanAvailability tailState;
        private ScanAvailability state;

        CountingScanSource(List<BlockPos> positions, boolean buffered, ScanAvailability tailState) {
            super(positions);
            this.buffered = buffered;
            this.tailState = tailState;
            this.state = positions.isEmpty() ? tailState : ScanAvailability.READY;
        }

        @Override public ScanAvailability availability() { return this.state; }
        @Override public boolean isBuffered() { return this.buffered; }

        @Override public Iterator<BlockPos> iterator() {
            this.state = this.positions.isEmpty() ? this.tailState : ScanAvailability.READY;
            Iterator<BlockPos> delegate = super.iterator();
            return new Iterator<>() {
                @Override public boolean hasNext() {
                    boolean available = delegate.hasNext();
                    if (!available) state = tailState;
                    return available;
                }

                @Override public BlockPos next() { return delegate.next(); }
            };
        }
    }

    private record ReadyTask(BlockPos pos) implements PrintTask {
        @Override public boolean shouldKeep(ClientLevel level, WorldSchematic schematic) { return true; }
        @Override public PrintTaskBuildResult buildAction(SchematicBlockContext context) { return PrintTaskBuildResult.SKIP; }
        @Override public PrintTaskAction createActionHandle(SchematicBlockContext context, Action action) { return null; }
    }

    @SuppressWarnings({"removal", "deprecation"})
    private static final class Fixture implements AutoCloseable {
        final ScanIntegrationFixture world;
        final SortedSchematicTargetQueue queue;
        final PrintTargetScheduler targets;
        final InteractionUtils interactions = new InteractionUtils();
        final HudStatsManager stats = new HudStatsManager(null, () -> 10L);
        final PrintWorkflowScheduler workflows = new PrintWorkflowScheduler(() -> 10L, null);
        final TestPrintHandler handler;
        final boolean previousSorted = Configs.Print.PRINT_SORT_TARGETS.getBooleanValue();
        final PrinterRuntime previousRuntime = RuntimeAccess.currentOrNull();
        final Field schematicField = SchematicWorldHandler.class.getDeclaredField("world");
        final Object previousSchematic;

        Fixture(boolean sorted) throws Exception {
            this(sorted, 1);
        }

        Fixture(boolean sorted, int count) throws Exception {
            this.world = new ScanIntegrationFixture(count);
            this.queue = new SortedSchematicTargetQueue(this.world.engine());
            this.targets = new PrintTargetScheduler(this.queue, () -> {});
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            var runtime = (PrinterRuntime) unsafe.allocateInstance(PrinterRuntime.class);
            set(runtime, PrinterRuntime.class, "interactionUtils", this.interactions);
            RuntimeAccess.install(runtime);
            this.schematicField.setAccessible(true);
            this.previousSchematic = this.schematicField.get(SchematicWorldHandler.INSTANCE);
            this.schematicField.set(SchematicWorldHandler.INSTANCE, this.world.schematic);
            this.targets.setSortedMode(sorted);
            Configs.Print.PRINT_SORT_TARGETS.setBooleanValue(false);
            this.handler = (TestPrintHandler) unsafe.allocateInstance(TestPrintHandler.class);
            set(handler, FeatureModuleBase.class, "id", "print");
            set(handler, FeatureModuleBase.class, "litematica", new LitematicaAdapter());
            set(handler, FeatureModuleBase.class, "hudStats", this.stats);
            set(handler, FeatureModuleBase.class, "level", this.world.level);
            set(handler, FeatureModuleBase.class, "player", this.world.player);
            set(handler, FeatureModuleBase.class, "scanEngine", this.world.engine());
            set(handler, FeatureModuleBase.class, "timing", RuntimeTiming.disabled());
            set(handler, FeatureModuleBase.class, "actionBroker",
                    new ActionBroker(() -> RuntimeEpoch.INITIAL, () -> null, null));
            var guiConstructor = Class.forName("me.aleksilassila.litematica.printer.handler.GuiBlockInfoBuffer")
                    .getDeclaredConstructor();
            guiConstructor.setAccessible(true);
            set(handler, FeatureModuleBase.class, "guiBlockInfoBuffer", guiConstructor.newInstance());
            set(handler, PrintHandler.class, "targets", this.targets);
            set(handler, PrintHandler.class, "guides", new Guides());
            set(handler, PrintHandler.class, "printTasks", this.workflows);
            set(handler, PrintHandler.class, "printSkipFilters", new String[0]);
        }

        @SuppressWarnings("unchecked")
        void addReadyWorkflow(BlockPos pos) throws Exception {
            Field field = PrintWorkflowScheduler.class.getDeclaredField("tasks");
            field.setAccessible(true);
            ((Map<BlockPos, PrintTask>) field.get(this.workflows)).put(pos, new ReadyTask(pos));
            this.workflows.tick(this.world.level, this.world.schematic);
            assertTrue(this.workflows.hasReadyWorkflow(), "Fixture must produce a real ready workflow prefix");
        }

        @Override public void close() throws Exception {
            this.schematicField.set(SchematicWorldHandler.INSTANCE, this.previousSchematic);
            set(null, RuntimeAccess.class, "current", this.previousRuntime);
            Configs.Print.PRINT_SORT_TARGETS.setBooleanValue(this.previousSorted);
            this.world.close();
        }
    }

    private static void set(Object target, Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
//#endif
