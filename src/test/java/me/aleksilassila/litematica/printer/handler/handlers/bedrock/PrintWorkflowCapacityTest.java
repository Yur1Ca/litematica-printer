//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.handler.FeatureModuleBase;
import me.aleksilassila.litematica.printer.handler.InventoryAvailabilityTracker;
import me.aleksilassila.litematica.printer.handler.handlers.PrintHandler;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintTaskBuildResult;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintWorkflowScheduler;
import me.aleksilassila.litematica.printer.handler.scan.ScanCache;
import me.aleksilassila.litematica.printer.handler.scan.ScanEngine;
import me.aleksilassila.litematica.printer.handler.scan.ScanIntent;
import me.aleksilassila.litematica.printer.integration.litematica.LitematicaAdapter;
import me.aleksilassila.litematica.printer.printer.MissingMaterialTracker;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.printer.action.ActionBroker;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.*;
import static org.junit.jupiter.api.Assertions.*;

class PrintWorkflowCapacityTest {
    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void waterBacklogPreservesQueuedWorkAndManualWaterDecisions(boolean materialBlocked) throws Exception {
        try (var f = new WaterSupportRegressionTest.WaterFixture()) {
            BlockPos queued = new BlockPos(-8, 64, 0);
            prepare(f, queued);
            var started = f.build(queued);
            assertTrue(started.hasAction());
            f.workflows.onActionQueued(started.actionHandle(), f.context(queued), started.action());
            for (int batch = 0; batch < 4; batch++) {
                for (int offset = 0; offset < 512; offset++) {
                    int index = batch * 512 + offset;
                    BlockPos pos = new BlockPos(index & 31, 64, index >>> 5);
                    prepare(f, pos);
                    if (materialBlocked) {
                        assertTrue(f.build(pos).hasAction());
                        f.workflows.onMaterialUnavailable(f.context(pos));
                    } else {
                        f.level.blocks.put(pos.below(), Blocks.AIR.defaultBlockState());
                        assertSame(PrintTaskBuildResult.SKIP, f.build(pos));
                    }
                }
                f.now++;
                f.workflows.tick(f.level, f.schematic);
                @SuppressWarnings("unchecked")
                var tasks = (Map<BlockPos, ?>) get(f.workflows, PrintWorkflowScheduler.class, "tasks");
                assertTrue(tasks.size() <= 1025, "Retain at most 1024 cold tasks plus the real queued workflow");
                assertTrue(f.workflows.isActiveTaskPos(queued), "A queued workflow cannot be evicted with cold tasks");
            }
            BlockPos first = new BlockPos(0, 64, 0);
            assertFalse(f.workflows.isActiveTaskPos(first), "The oldest unstarted task yields temporary delivery ownership");
            assertTrue(f.level.getBlockState(first).isAir());

            f.workflows.onInventoryAvailabilityChanged();
            f.level.blocks.put(first.below(), Blocks.STONE.defaultBlockState());
            if (materialBlocked) {
                assertTrue(f.build(first).hasAction(), "A rescan can reconstruct an evicted task after inventory gain");
            } else {
                assertSame(PrintTaskBuildResult.SKIP, f.build(first),
                        "Cold-task eviction and inventory gains must not undo a manual-water decision");
                assertFalse(f.workflows.isActiveTaskPos(first));
            }
            assertTrue(f.workflows.isActiveTaskPos(queued));
        }
    }

    @Test void leavingTheScopeReleasesColdWaterTasksButPreservesQueuedOwnership() throws Exception {
        boolean oldSorted = Configs.Print.PRINT_SORT_TARGETS.getBooleanValue();
        var field = fi.dy.masa.litematica.world.SchematicWorldHandler.class.getDeclaredField("world");
        field.setAccessible(true);
        Object oldSchematic = field.get(fi.dy.masa.litematica.world.SchematicWorldHandler.INSTANCE);
        try (var f = new WaterSupportRegressionTest.WaterFixture()) {
            Configs.Print.PRINT_SORT_TARGETS.setBooleanValue(false);
            field.set(fi.dy.masa.litematica.world.SchematicWorldHandler.INSTANCE, f.schematic);
            BlockPos cold = new BlockPos(0, 64, 0);
            BlockPos queued = new BlockPos(4, 64, 0);
            prepare(f, cold);
            prepare(f, queued);
            assertTrue(f.build(cold).hasAction());
            f.workflows.onMaterialUnavailable(f.context(cold));
            var started = f.build(queued);
            f.workflows.onActionQueued(started.actionHandle(), f.context(queued), started.action());
            var runtime = f.runtime;
            set(runtime, PrinterRuntime.class, "timing", RuntimeTiming.disabled());
            set(runtime, PrinterRuntime.class, "scanEngine", new ScanEngine(() -> RuntimeEpoch.INITIAL, () -> f.level, new ScanCache()));
            set(runtime, PrinterRuntime.class, "actionBroker", new ActionBroker(() -> RuntimeEpoch.INITIAL, () -> null, null));
            set(runtime, PrinterRuntime.class, "cooldownUtils", new CooldownUtils());
            set(runtime, PrinterRuntime.class, "missingMaterials", new MissingMaterialTracker());
            set(runtime, PrinterRuntime.class, "inventoryAvailability", new InventoryAvailabilityTracker());
            set(runtime, PrinterRuntime.class, "litematica", new LitematicaAdapter());
            set(runtime, PrinterRuntime.class, "materialRequests",
                    new me.aleksilassila.litematica.printer.integration.inventory.MaterialRequestCoordinator(List.of(
                            new me.aleksilassila.litematica.printer.integration.inventory.InventoryProvider() {
                                @Override public String id() { return "workflow-test"; }
                                @Override public me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation request(
                                        me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest request) {
                                    return new me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation(request.token(),
                                            me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation.State.UNAVAILABLE);
                                }
                            })));
            var handler = new ScopedPrintHandler(runtime);
            set(handler, PrintHandler.class, "printTasks", f.workflows);
            handler.scope = new PrinterBox(16, 64, 0, 31, 64, 7);

            handler.positions().iterator().hasNext();

            assertFalse(f.workflows.isActiveTaskPos(cold), "Cold tasks outside the interaction scope must not accumulate");
            assertTrue(f.workflows.isActiveTaskPos(queued), "Leaving the scope cannot cancel queued ownership");
            handler.scope = new PrinterBox(0, 64, 0, 7, 64, 7);
            f.workflows.onInventoryAvailabilityChanged();
            assertTrue(f.build(cold).hasAction());
            assertTrue(f.workflows.isActiveTaskPos(queued));
        } finally {
            field.set(fi.dy.masa.litematica.world.SchematicWorldHandler.INSTANCE, oldSchematic);
            Configs.Print.PRINT_SORT_TARGETS.setBooleanValue(oldSorted);
        }
    }

    @Test void nativeIceBreakOwnershipSurvivesColdTaskEvictionAndUnrelatedUpdates() throws Exception {
        try (var f = new WaterSupportRegressionTest.WaterFixture()) {
            BlockPos owned = new BlockPos(-8, 64, 0);
            prepare(f, owned);
            f.level.blocks.put(owned, Blocks.ICE.defaultBlockState());
            assertSame(PrintTaskBuildResult.SKIP, f.build(owned));
            assertEquals(List.of(owned), f.mining.requests, "The workflow really submitted its native ice break");
            for (int index = 0; index < 1536; index++) {
                BlockPos pos = new BlockPos(index & 31, 64, index >>> 5);
                prepare(f, pos);
                f.level.blocks.put(pos.below(), Blocks.AIR.defaultBlockState());
                assertSame(PrintTaskBuildResult.SKIP, f.build(pos));
                f.workflows.wake(new BlockPos(10_000 + index, 64, 10_000));
            }
            assertTrue(f.workflows.isActiveTaskPos(owned));
            @SuppressWarnings("unchecked")
            var tasks = (Map<BlockPos, ?>) get(f.workflows, PrintWorkflowScheduler.class, "tasks");
            assertTrue(tasks.size() <= 1025);
            assertTrue(((me.aleksilassila.litematica.printer.handler.handlers.print.PrintTask) tasks.get(owned)).hasStarted());
            var wakes = (it.unimi.dsi.fastutil.longs.LongSet) get(f.workflows, PrintWorkflowScheduler.class, "wakeKeys");
            assertTrue(wakes.size() <= tasks.size(), "Unrelated world updates cannot grow a second unbounded task store");
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void releasingAnIdleWorkflowReoffersOnlyTheCurrentSourceWithoutDirtyingWorld(boolean sorted) throws Exception {
        boolean oldSorted = Configs.Print.PRINT_SORT_TARGETS.getBooleanValue();
        var field = fi.dy.masa.litematica.world.SchematicWorldHandler.class.getDeclaredField("world");
        field.setAccessible(true);
        Object oldSchematic = field.get(fi.dy.masa.litematica.world.SchematicWorldHandler.INSTANCE);
        try (var f = new WaterSupportRegressionTest.WaterFixture()) {
            Configs.Print.PRINT_SORT_TARGETS.setBooleanValue(sorted);
            field.set(fi.dy.masa.litematica.world.SchematicWorldHandler.INSTANCE, f.schematic);
            BlockPos owned = new BlockPos(0, 64, 0);
            prepare(f, owned);
            var placed = f.build(owned);
            assertTrue(placed.hasAction());
            f.workflows.onActionSuccess(placed.actionHandle(), f.context(owned), placed.action());
            f.level.blocks.put(owned, Blocks.ICE.defaultBlockState());
            f.workflows.wake(owned);
            f.now++;
            f.workflows.tick(f.level, f.schematic);
            assertTrue(f.workflows.isBreakingTaskPos(owned));

            var cache = new ScanCache();
            var scan = new ScanEngine(() -> RuntimeEpoch.INITIAL, () -> f.level, cache);
            scan.beginTick(f.level, f.schematic, f.now);
            // Establish both real owner sessions without advancing either delivery cursor.
            scan.rawIterable("print", new PrinterBox(owned), null, 0, pos -> true);
            scan.rawIterable("print_sorted", new PrinterBox(owned), null, 0, pos -> true);
            var store = get(cache, ScanCache.class, "sessions");
            @SuppressWarnings("unchecked")
            var sessions = (Map<String, ?>) get(store, store.getClass(), "sessions");
            Object current = sessions.get((sorted ? "print_sorted" : "print") + ":CUSTOM");
            Object other = sessions.get((sorted ? "print" : "print_sorted") + ":CUSTOM");
            long dirtyBefore = scan.dirtyVersion();
            var handler = scopedHandler(f, scan);
            handler.scope = new PrinterBox(owned);
            handler.emptyScope = true;

            handler.positions().iterator().hasNext();

            assertFalse(f.workflows.isActiveTaskPos(owned));
            assertEquals(java.util.Set.of(owned), get(current, current.getClass(), "pendingReoffers"));
            assertEquals(java.util.Set.of(), get(other, other.getClass(), "pendingReoffers"));
            assertEquals(dirtyBefore, scan.dirtyVersion(), "Releasing delivery is not a world invalidation");
            assertTrue(f.level.getBlockState(owned).is(Blocks.ICE));
            assertTrue(f.mining.requests.isEmpty());
        } finally {
            field.set(fi.dy.masa.litematica.world.SchematicWorldHandler.INSTANCE, oldSchematic);
            Configs.Print.PRINT_SORT_TARGETS.setBooleanValue(oldSorted);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"water", "strip"})
    void cancellingARealUnsentQueueReturnsTheWorkflowToColdRetention(String kind) throws Exception {
        boolean oldStrip = Configs.Print.STRIP_LOGS.getBooleanValue();
        try (var f = new WaterSupportRegressionTest.WaterFixture()) {
            Configs.Print.STRIP_LOGS.setBooleanValue(true);
            BlockPos cancelled = new BlockPos(-8, 64, 0);
            prepare(f, cancelled);
            var workflows = kind.equals("water") ? f.workflows : new PrintWorkflowScheduler(() -> f.now,
                    block -> block == Blocks.STRIPPED_OAK_LOG ? Blocks.OAK_LOG : null);
            if (kind.equals("strip")) f.schematic.states.put(cancelled, Blocks.STRIPPED_OAK_LOG.defaultBlockState());
            var context = f.context(cancelled);
            var built = workflows.buildAction(context);
            assertTrue(built.hasAction());
            var queue = new me.aleksilassila.litematica.printer.printer.ActionManager();
            assertTrue(queue.queueClick(cancelled.below(), net.minecraft.core.Direction.UP,
                    net.minecraft.world.phys.Vec3.ZERO, false));
            assertTrue(queue.setQueueCompletionListener(result -> {
                assertFalse(result.isSent());
                // Match the deferred executor callback, which invokes the action handle directly.
                built.actionHandle().onCancelled(context, built.action());
            }));
            workflows.onActionQueued(built.actionHandle(), context, built.action());
            @SuppressWarnings("unchecked")
            var tasks = (Map<BlockPos, me.aleksilassila.litematica.printer.handler.handlers.print.PrintTask>)
                    get(workflows, PrintWorkflowScheduler.class, "tasks");
            assertTrue(tasks.get(cancelled).hasStarted(), "The live queue must hold ownership before cancellation");

            queue.cancelQueue();

            assertFalse(tasks.get(cancelled).hasStarted(), "A never-sent cancellation cannot permanently bypass cold capacity");
            BlockPos sent = new BlockPos(-12, 64, 0);
            prepare(f, sent);
            if (kind.equals("strip")) f.schematic.states.put(sent, Blocks.STRIPPED_OAK_LOG.defaultBlockState());
            var submitted = workflows.buildAction(f.context(sent));
            assertTrue(submitted.hasAction());
            workflows.onActionSuccess(submitted.actionHandle(), f.context(sent), submitted.action());
            submitted.actionHandle().onCancelled(f.context(sent), submitted.action());
            assertTrue(tasks.get(sent).hasStarted(), "A late cancellation cannot revoke a previously sent workflow");
            f.now++;
            workflows.tick(f.level, f.schematic);
            for (int index = 0; index < 1536; index++) {
                BlockPos pos = new BlockPos(index & 31, 64, index >>> 5);
                prepare(f, pos);
                var action = workflows.buildAction(f.context(pos));
                assertTrue(action.hasAction());
                workflows.onMaterialUnavailable(f.context(pos));
            }
            assertFalse(workflows.isActiveTaskPos(cancelled));
            assertTrue(workflows.isActiveTaskPos(sent));
            assertTrue(tasks.size() <= 1025);
            assertTrue(f.level.getBlockState(cancelled).isAir());
        } finally {
            Configs.Print.STRIP_LOGS.setBooleanValue(oldStrip);
        }
    }

    @Test void startedAdmissionIsBoundedWithoutBlockingOwnedProgressOrLosingColdWork() throws Exception {
        try (var f = new WaterSupportRegressionTest.WaterFixture()) {
            for (int index = 0; index < 128; index++) {
                BlockPos pos = new BlockPos(index & 31, 64, index >>> 5);
                prepare(f, pos);
                var built = f.build(pos);
                assertTrue(built.hasAction());
                f.workflows.onActionSuccess(built.actionHandle(), f.context(pos), built.action());
            }
            BlockPos pending = new BlockPos(0, 64, 4);
            BlockPos nativeBreak = pending.east();
            prepare(f, pending);
            prepare(f, nativeBreak);
            f.level.blocks.put(nativeBreak, Blocks.ICE.defaultBlockState());

            assertSame(PrintTaskBuildResult.SKIP, f.build(pending), "The 129th workflow must wait before starting another action");
            assertSame(PrintTaskBuildResult.SKIP, f.build(nativeBreak));
            assertTrue(f.mining.requests.isEmpty(), "Admission must run before buildAction can start native mining");

            BlockPos owned = new BlockPos(0, 64, 0);
            f.level.blocks.put(owned, Blocks.ICE.defaultBlockState());
            assertSame(PrintTaskBuildResult.SKIP, f.build(owned));
            assertEquals(List.of(owned), f.mining.requests, "The cap must allow already owned workflows to advance");
            f.level.blocks.put(owned, Blocks.WATER.defaultBlockState());
            f.workflows.wake(owned);
            f.now++;
            f.workflows.tick(f.level, f.schematic);

            assertFalse(f.workflows.isActiveTaskPos(owned));
            assertTrue(f.build(pending).hasAction(), "Completing one owned workflow frees admission for the retained cold task");
            assertSame(PrintTaskBuildResult.SKIP, f.build(nativeBreak));
            assertTrue(f.mining.requests.contains(nativeBreak), "A deferred native-break target can start after capacity is freed");
        }
    }

    @Test void confirmedIdleIceOutsideTheScopeCannotExhaustNewWaterAdmission() throws Exception {
        try (var f = new WaterSupportRegressionTest.WaterFixture()) {
            var previousTargets = new java.util.ArrayList<BlockPos>();
            for (int index = 0; index < 128; index++) {
                BlockPos pos = new BlockPos(index & 31, 64, index >>> 5);
                previousTargets.add(pos);
                prepare(f, pos);
                var placed = f.build(pos);
                assertTrue(placed.hasAction());
                f.workflows.onActionSuccess(placed.actionHandle(), f.context(pos), placed.action());
                // The placement has reached its real success callback and its world state has
                // arrived. No queue callback or native break owns these idle ice blocks.
                f.level.blocks.put(pos, Blocks.ICE.defaultBlockState());
                f.workflows.wake(pos);
            }
            f.now = 1L;
            f.workflows.tick(f.level, f.schematic);
            for (BlockPos pos : previousTargets) {
                assertTrue(f.workflows.isBreakingTaskPos(pos));
            }
            assertTrue(f.mining.requests.isEmpty(), "These confirmed ice targets have not started native mining");
            var started = (it.unimi.dsi.fastutil.longs.LongSet)
                    get(f.workflows, PrintWorkflowScheduler.class, "startedKeys");
            assertEquals(128, started.size());

            BlockPos fresh = new BlockPos(128, 64, 0);
            prepare(f, fresh);
            assertSame(PrintTaskBuildResult.SKIP, f.build(fresh), "The original 128-task admission cap remains intact");

            // Match PrintHandler's scope transition without switching Print off, gaining
            // materials, completing the old workflows or fabricating a world update.
            f.workflows.retainWithin(List.of(new PrinterBox(128, 64, 0, 135, 64, 7)), pos -> false);

            assertTrue(f.build(fresh).hasAction(),
                    "Confirmed idle ice outside the source cannot permanently occupy all started slots");
            for (BlockPos pos : previousTargets) {
                assertTrue(f.level.getBlockState(pos).is(Blocks.ICE), "Releasing delivery must preserve the world's ice");
                assertTrue(f.schematic.getBlockState(pos).is(Blocks.WATER));
            }
            assertTrue(f.mining.requests.isEmpty());

            // Returning discovery can reconstruct the same remaining work from the unchanged
            // ice/water mismatch; releasing an idle task cannot mark its world fact complete.
            f.workflows.retainWithin(List.of(new PrinterBox(0, 64, 0, 31, 64, 3)), pos -> false);
            BlockPos returned = previousTargets.getFirst();
            assertSame(PrintTaskBuildResult.SKIP, f.build(returned));
            assertEquals(List.of(returned), f.mining.requests);
            assertTrue(f.workflows.isActiveTaskPos(returned));
        }
    }

    @Test void confirmedIdleLogsOutsideTheScopeCannotExhaustNewWaterAdmission() throws Exception {
        boolean oldStrip = Configs.Print.STRIP_LOGS.getBooleanValue();
        try (var f = new WaterSupportRegressionTest.WaterFixture()) {
            Configs.Print.STRIP_LOGS.setBooleanValue(true);
            var workflows = new PrintWorkflowScheduler(() -> f.now,
                    block -> block == Blocks.STRIPPED_OAK_LOG ? Blocks.OAK_LOG : null);
            for (int index = 0; index < 128; index++) {
                BlockPos pos = new BlockPos(index & 31, 64, index >>> 5);
                prepare(f, pos);
                f.schematic.states.put(pos, Blocks.STRIPPED_OAK_LOG.defaultBlockState());
                var placed = workflows.buildAction(f.context(pos));
                assertTrue(placed.hasAction());
                workflows.onActionSuccess(placed.actionHandle(), f.context(pos), placed.action());
                f.level.blocks.put(pos, Blocks.OAK_LOG.defaultBlockState());
                workflows.wake(pos);
            }
            f.now++;
            workflows.tick(f.level, f.schematic);
            @SuppressWarnings("unchecked")
            var tasks = (Map<BlockPos, ?>) get(workflows, PrintWorkflowScheduler.class, "tasks");
            assertEquals(128, tasks.size());
            for (Object task : tasks.values()) {
                assertEquals("STRIP_LOG", get(task, task.getClass(), "stage").toString(),
                        "A confirmed source log must be idle rather than waiting for its placement receipt");
            }
            BlockPos fresh = new BlockPos(128, 64, 0);
            prepare(f, fresh);
            assertSame(PrintTaskBuildResult.SKIP, workflows.buildAction(f.context(fresh)),
                    "The 128-task admission cap must still hold before leaving the old scope");

            workflows.retainWithin(List.of(new PrinterBox(128, 64, 0, 135, 64, 7)), pos -> false);

            assertTrue(workflows.buildAction(f.context(fresh)).hasAction(),
                    "Confirmed idle logs outside the source cannot permanently occupy every started slot");
            assertTrue(f.level.getBlockState(new BlockPos(0, 64, 0)).is(Blocks.OAK_LOG));
            assertTrue(f.schematic.getBlockState(new BlockPos(0, 64, 0)).is(Blocks.STRIPPED_OAK_LOG));
            assertTrue(f.mining.requests.isEmpty());
        } finally {
            Configs.Print.STRIP_LOGS.setBooleanValue(oldStrip);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"queued", "sent_wait", "native"})
    void leavingTheScopeKeepsCurrentOwnershipUntilItActuallyEnds(String ownership) throws Exception {
        try (var f = new WaterSupportRegressionTest.WaterFixture()) {
            BlockPos owned = new BlockPos(0, 64, 0);
            prepare(f, owned);
            var built = f.build(owned);
            assertTrue(built.hasAction());
            if (ownership.equals("queued")) {
                f.workflows.onActionQueued(built.actionHandle(), f.context(owned), built.action());
            } else {
                f.workflows.onActionSuccess(built.actionHandle(), f.context(owned), built.action());
            }
            boolean nativeHold = ownership.equals("native");
            if (nativeHold) {
                f.level.blocks.put(owned, Blocks.ICE.defaultBlockState());
                f.workflows.wake(owned);
                f.now++;
                f.workflows.tick(f.level, f.schematic);
                assertTrue(f.workflows.isBreakingTaskPos(owned));
            }
            var outside = List.of(new PrinterBox(128, 64, 0, 135, 64, 7));

            f.workflows.retainWithin(outside, pos -> nativeHold && pos.equals(owned));

            assertTrue(f.workflows.isActiveTaskPos(owned), "Scope changes cannot revoke " + ownership);
            assertTrue(f.mining.requests.isEmpty(), "Retention itself cannot submit or cancel a mining action");

            if (ownership.equals("queued")) {
                f.workflows.onActionCancelled(built.actionHandle(), f.context(owned), built.action());
            } else if (!nativeHold) {
                f.level.blocks.put(owned, Blocks.ICE.defaultBlockState());
                f.workflows.wake(owned);
                f.now++;
                f.workflows.tick(f.level, f.schematic);
            }
            f.workflows.retainWithin(outside, pos -> false);

            assertFalse(f.workflows.isActiveTaskPos(owned), "An ended owner cannot become permanent started history");
            assertTrue(f.mining.requests.isEmpty());
        }
    }

    private static void prepare(WaterSupportRegressionTest.WaterFixture f, BlockPos pos) {
        f.schematic.states.put(pos, Blocks.WATER.defaultBlockState());
        f.level.blocks.put(pos, Blocks.AIR.defaultBlockState());
        f.level.blocks.put(pos.below(), Blocks.STONE.defaultBlockState());
    }

    private static ScopedPrintHandler scopedHandler(WaterSupportRegressionTest.WaterFixture f, ScanEngine scan) throws Exception {
        var runtime = f.runtime;
        set(runtime, PrinterRuntime.class, "timing", RuntimeTiming.disabled());
        set(runtime, PrinterRuntime.class, "scanEngine", scan);
        set(runtime, PrinterRuntime.class, "actionBroker", new ActionBroker(() -> RuntimeEpoch.INITIAL, () -> null, null));
        set(runtime, PrinterRuntime.class, "cooldownUtils", new CooldownUtils());
        set(runtime, PrinterRuntime.class, "missingMaterials", new MissingMaterialTracker());
        set(runtime, PrinterRuntime.class, "inventoryAvailability", new InventoryAvailabilityTracker());
        set(runtime, PrinterRuntime.class, "litematica", new LitematicaAdapter());
        set(runtime, PrinterRuntime.class, "materialRequests",
                new me.aleksilassila.litematica.printer.integration.inventory.MaterialRequestCoordinator(List.of(
                        new me.aleksilassila.litematica.printer.integration.inventory.InventoryProvider() {
                            @Override public String id() { return "workflow-test"; }
                            @Override public me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation request(
                                    me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest request) {
                                return new me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation(request.token(),
                                        me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation.State.UNAVAILABLE);
                            }
                        })));
        var handler = new ScopedPrintHandler(runtime);
        set(handler, PrintHandler.class, "printTasks", f.workflows);
        return handler;
    }

    private static final class ScopedPrintHandler extends PrintHandler {
        PrinterBox scope;
        boolean emptyScope;
        ScopedPrintHandler(PrinterRuntime runtime) { super(runtime); }
        Iterable<BlockPos> positions() { return super.getIterationPositions(scope); }
        @Override protected List<PrinterBox> getScanSourceBoxes(PrinterBox box) { return emptyScope ? List.of() : List.of(box); }
        @Override protected boolean canReachIterationPosition(BlockPos pos) { return true; }
        @Override protected boolean isInSelectionRange(BlockPos pos) { return true; }
        @Override protected Iterable<BlockPos> getCachedFilteredIterationPositions(
                PrinterBox box, ScanIntent intent, Predicate<BlockPos> predicate) { return List.of(); }
    }
}
//#endif
