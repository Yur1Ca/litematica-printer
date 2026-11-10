//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.scan;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Predicate;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.guide.Guides;
import me.aleksilassila.litematica.printer.handler.FeatureModuleBase;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.handler.handlers.PrintHandler;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintPlacementResult;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintTargetScheduler;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintWorkflowScheduler;
import me.aleksilassila.litematica.printer.handler.handlers.print.SortedSchematicTargetQueue;
import me.aleksilassila.litematica.printer.integration.litematica.LitematicaAdapter;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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

            f.stats.confirmBlockUpdate(BlockPos.ZERO, Blocks.COBBLESTONE.defaultBlockState());
            f.world.level.world.put(BlockPos.ZERO, Blocks.COBBLESTONE.defaultBlockState());
            if (sorted) assertEquals(BlockPos.ZERO, f.queue.iterator().next());
            assertFalse(f.handler.canIterationBlockPos(BlockPos.ZERO));
            assertFalse(f.targets.hasRunnableWork(),
                    "A matching world state must retire the retained target instead of keeping FULL busy forever");
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void recentBreakWaitRetainsOnlyTheAffectedTarget(boolean sorted) throws Exception {
        try (var f = new Fixture(sorted)) {
            f.interactions.markRecentlyBroken(BlockPos.ZERO);

            assertFalse(f.handler.canIterationBlockPos(BlockPos.ZERO));
            assertTrue(f.targets.hasRunnableWork(), "A recent-break marker is a transient wait");
            if (sorted) assertEquals(BlockPos.ZERO, f.queue.iterator().next());
            else assertEquals(java.util.List.of(BlockPos.ZERO), f.targets.retainedTargets());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void resolvedRetryIsDiscardedWhenItsTargetAlreadyMatches(boolean sorted) throws Exception {
        try (var f = new Fixture(sorted)) {
            f.targets.onResult(BlockPos.ZERO, PrintPlacementResult.deferred(false), false);
            if (sorted) assertEquals(BlockPos.ZERO, f.queue.iterator().next());
            f.world.level.world.put(BlockPos.ZERO, Blocks.COBBLESTONE.defaultBlockState());

            assertFalse(f.handler.canIterationBlockPos(BlockPos.ZERO));

            assertFalse(f.targets.hasRunnableWork(),
                    "An obsolete retry cannot keep the scanner awake after another action resolved it");
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

    private static final class TestPrintHandler extends PrintHandler {
        boolean unreachable;
        private TestPrintHandler() { super(null); }
        Iterable<BlockPos> positions() { return super.getIterationPositions(new PrinterBox(BlockPos.ZERO)); }
        @Override protected List<PrinterBox> getScanSourceBoxes(PrinterBox box) { return List.of(box); }
        @Override protected boolean canReachIterationPosition(BlockPos pos) { return !this.unreachable; }
        @Override protected boolean isInSelectionRange(BlockPos pos) { return true; }
        @Override protected Iterable<BlockPos> getCachedFilteredIterationPositions(
                PrinterBox box, ScanIntent intent, Predicate<BlockPos> predicate) { return List.of(); }
    }

    @SuppressWarnings({"removal", "deprecation"})
    private static final class Fixture implements AutoCloseable {
        final ScanIntegrationFixture world = new ScanIntegrationFixture(1);
        final SortedSchematicTargetQueue queue = new SortedSchematicTargetQueue(null);
        final PrintTargetScheduler targets = new PrintTargetScheduler(queue, () -> {});
        final InteractionUtils interactions = new InteractionUtils();
        final HudStatsManager stats = new HudStatsManager(null, () -> 10L);
        final TestPrintHandler handler;
        final boolean previousSorted = Configs.Print.PRINT_SORT_TARGETS.getBooleanValue();
        final PrinterRuntime previousRuntime = RuntimeAccess.currentOrNull();
        final Field schematicField = SchematicWorldHandler.class.getDeclaredField("world");
        final Object previousSchematic;

        Fixture(boolean sorted) throws Exception {
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
            set(handler, FeatureModuleBase.class, "litematica", new LitematicaAdapter());
            set(handler, FeatureModuleBase.class, "hudStats", this.stats);
            set(handler, FeatureModuleBase.class, "level", this.world.level);
            set(handler, PrintHandler.class, "targets", this.targets);
            set(handler, PrintHandler.class, "guides", new Guides());
            set(handler, PrintHandler.class, "printTasks", new PrintWorkflowScheduler(() -> 10L, null));
            set(handler, PrintHandler.class, "printSkipFilters", new String[0]);
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
