//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.scan;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.FeatureModuleBase;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.handler.handlers.PrintHandler;
import me.aleksilassila.litematica.printer.handler.handlers.print.FallingPlacementTracker;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintPlacementResult;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintTargetScheduler;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual guide inspection and sorted source supply after a temporary local rejection. */
class PrintResidualSupplyTest {
    private static final BlockPos TARGET = new BlockPos(0, 1, 0);

    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test void aGuideRejectedForMissingSupportIsReofferedAfterItsNeighbourBecomesReady() throws Exception {
        try (var f = new Fixture(Blocks.TORCH.defaultBlockState())) {
            f.tick(1);
            assertEquals(List.of(TARGET), f.positions());
            assertFalse(f.handler.canIterationBlockPos(TARGET), "The real Torch/Default guide has no surviving placement yet");
            assertTrue(f.handler.getPendingIterationWorkCount() > 0,
                    "The HUD must distinguish a deferred mismatch from an empty completed workset");
            long dirty = f.world.engine().dirtyVersion();
            f.world.level.world.put(TARGET.below(), Blocks.STONE.defaultBlockState());
            f.tick(2);
            assertTrue(f.positions().isEmpty(), "A temporary rejection must not become a same-tick hot loop");
            f.tick(6);

            assertEquals(List.of(TARGET), f.positions(), "Finishing the offer cursor cannot forget a still-mismatching ordinary target");
            assertTrue(f.handler.canIterationBlockPos(TARGET), "The real guide is executable once support is present");
            assertEquals(dirty, f.world.engine().dirtyVersion(), "This local batch tail must not depend on a delivered block event");
        }
    }

    @Test void aNeighbourUpdateWakesATimedOrdinaryWaitBeforeItsDeadline() throws Exception {
        try (var f = new Fixture(Blocks.TORCH.defaultBlockState())) {
            f.tick(1);
            assertEquals(List.of(TARGET), f.positions());
            assertFalse(f.handler.canIterationBlockPos(TARGET));
            f.world.level.world.put(TARGET.below(), Blocks.STONE.defaultBlockState());
            f.targets.wake(TARGET.below());
            f.tick(2);
            assertEquals(List.of(TARGET), f.positions());
            assertTrue(f.handler.canIterationBlockPos(TARGET));
        }
    }

    @Test void materialWaitsNeedInventoryWakeupInsteadOfNeighbourUpdateHotRetries() throws Exception {
        try (var f = new Fixture(Blocks.TORCH.defaultBlockState())) {
            f.tick(1);
            assertEquals(List.of(TARGET), f.positions());
            f.targets.waitForMaterial(TARGET, 1L, false);
            f.world.level.world.put(TARGET.below(), Blocks.STONE.defaultBlockState());
            f.targets.wake(TARGET.below());
            f.tick(6);
            assertTrue(f.positions().isEmpty());
            f.targets.invalidateCandidates();
            f.tick(7);
            assertEquals(List.of(TARGET), f.positions());
        }
    }

    @Test void aOneTickFallingColumnReservationCannotLoseTheOrdinaryTargetAfterItExpires() throws Exception {
        boolean oldFalling = Configs.Placement.FALLING_CHECK.getBooleanValue();
        try (var f = new Fixture(Blocks.SAND.defaultBlockState())) {
            Configs.Placement.FALLING_CHECK.setBooleanValue(true);
            f.tick(1);
            f.falling.mark(TARGET.below(), Blocks.SAND.defaultBlockState(), Blocks.AIR.defaultBlockState(), 1);
            assertEquals(List.of(TARGET), f.positions());
            assertFalse(f.handler.canIterationBlockPos(TARGET), "The pending lower placement really owns this column this tick");
            long dirty = f.world.engine().dirtyVersion();
            f.tick(6);

            assertEquals(List.of(TARGET), f.positions(), "An expired falling reservation must leave bounded local retry supply");
            assertTrue(f.handler.canIterationBlockPos(TARGET));
            assertEquals(dirty, f.world.engine().dirtyVersion());
        } finally {
            Configs.Placement.FALLING_CHECK.setBooleanValue(oldFalling);
        }
    }

    @Test void aSentPlacementWithoutAnyWorldChangeIsReofferedByItsExistingConfirmationTimeout() throws Exception {
        try (var f = new Fixture(Blocks.STONE.defaultBlockState())) {
            f.tick(1);
            assertEquals(List.of(TARGET), f.positions());
            f.stats.trackExpectedBlockState(HudStatsManager.Mode.PRINT, TARGET, Blocks.STONE.defaultBlockState());
            f.targets.onResult(TARGET, new PrintPlacementResult(true, false,
                    PrintPlacementResult.TaskEvent.SUCCESS, -1), false);
            f.tick(2);
            assertTrue(f.positions().isEmpty(), "An already sent placement must await its existing confirmation path");
            f.tick(13);
            f.stats.tick();

            assertEquals(List.of(TARGET), f.positions());
            assertTrue(f.world.level.getBlockState(TARGET).isAir(), "No speculative world write may satisfy the target");
        }
    }

    /** Reuse the established PRINT fixture without widening its test-only private API. */
    private static final class Fixture implements AutoCloseable {
        final AutoCloseable delegate;
        final ScanIntegrationFixture world;
        final PrintHandler handler;
        final PrintTargetScheduler targets;
        final FallingPlacementTracker falling;
        final HudStatsManager stats;
        final Method positions;

        Fixture(BlockState required) throws Exception {
            Class<?> type = Class.forName(PrintScanRecoveryTest.class.getName() + "$Fixture");
            var constructor = type.getDeclaredConstructor(boolean.class);
            constructor.setAccessible(true);
            Object fixture = constructor.newInstance(true);
            delegate = (AutoCloseable) fixture;
            world = (ScanIntegrationFixture) get(fixture, type, "world");
            handler = (PrintHandler) get(fixture, type, "handler");
            targets = (PrintTargetScheduler) get(fixture, type, "targets");
            set(targets, PrintTargetScheduler.class, "resetScanSources", (Runnable) () -> {
                world.cache.resetOwner("print");
                world.cache.resetOwner("print_sorted");
            });
            falling = (FallingPlacementTracker) get(handler, PrintHandler.class, "fallingPlacements");
            var replacement = world.newChunk(0);
            replacement.getSections()[4].setBlockState(TARGET.getX(), TARGET.getY(), TARGET.getZ(), required);
            world.chunks.put(0L, replacement);
            set(handler, handler.getClass(), "sourceBoxes", List.of(new PrinterBox(TARGET)));
            positions = handler.getClass().getDeclaredMethod("positions");
            positions.setAccessible(true);
            Configs.Print.PRINT_SORT_TARGETS.setBooleanValue(true);
            var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            var client = (Minecraft) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(Minecraft.class);
            client.level = world.level;
            client.player = world.player;
            stats = new HudStatsManager(client, () -> world.level.tick);
            set(handler, FeatureModuleBase.class, "hudStats", stats);
            set(RuntimeAccess.get(), PrinterRuntime.class, "hudStats", stats);
            set(RuntimeAccess.get(), PrinterRuntime.class, "scanEngine", world.engine());
        }

        void tick(long tick) throws Exception {
            world.tick(tick);
            set(RuntimeAccess.get(), PrinterRuntime.class, "currentTick", tick);
        }
        @SuppressWarnings("unchecked") List<BlockPos> positions() throws Exception {
            return ScanIntegrationFixture.drain((Iterable<BlockPos>) positions.invoke(handler));
        }
        @Override public void close() throws Exception { delegate.close(); }
    }

    private static Object get(Object target, Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void set(Object target, Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
}
//#endif
