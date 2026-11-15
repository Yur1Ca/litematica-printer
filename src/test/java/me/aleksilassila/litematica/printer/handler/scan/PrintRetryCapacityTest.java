//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.scan;

import java.util.List;
import java.util.ArrayList;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintPlacementResult;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintTargetScheduler;
import me.aleksilassila.litematica.printer.handler.handlers.print.SortedSchematicTargetQueue;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PrintRetryCapacityTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test void aRepeatedExplicitReofferDoesNotWaitForTheUnscannedSelectionTail() throws Exception {
        int previousWork = me.aleksilassila.litematica.printer.config.Configs.Core.WORK_PER_TICK.getIntegerValue();
        me.aleksilassila.litematica.printer.config.Configs.Core.WORK_PER_TICK.setIntegerValue(256);
        try {
            for (String owner : List.of("mine", "print")) {
                try (var f = new ScanIntegrationFixture(1)) {
                    ScanIntent intent = owner.equals("mine") ? ScanIntent.MINE : ScanIntent.PRINT;
                    BlockPos target = owner.equals("mine") ? new BlockPos(0, 15, 0) : BlockPos.ZERO;
                    if (owner.equals("mine")) f.level.world.put(target, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                    var scope = List.of(new PrinterBox(0, 0, 0, 15, 15, 15));
                    f.tick(1);
                    var original = f.scan(owner, scope, 1, intent).iterator();
                    assertEquals(target, original.next());
                    for (int tick = 2; tick <= 4; tick++) {
                        f.engine().reoffer(owner, target);
                        long dirty = f.engine().dirtyVersion();
                        int reads = f.level.readsAt(target);
                        f.tick(tick);
                        var retry = f.scan(owner, scope, 1, intent).iterator();
                        assertEquals(List.of(target), drain(retry),
                                "An explicit ownership release must return at its rank without walking the large unscanned tail");
                        assertFalse(original.hasNext(), "A new delivery must still invalidate the old iterator");
                        assertEquals(dirty, f.engine().dirtyVersion());
                        assertEquals(reads, f.level.readsAt(target), "Retry must retain shared positive facts");
                    }
                }
            }
        } finally {
            me.aleksilassila.litematica.printer.config.Configs.Core.WORK_PER_TICK.setIntegerValue(previousWork);
        }
    }

    @Test void anExplicitMineReofferKeepsItsRankWhenWorldNotificationHistoryIsFull() throws Exception {
        int previousWork = me.aleksilassila.litematica.printer.config.Configs.Core.WORK_PER_TICK.getIntegerValue();
        try (var f = new ScanIntegrationFixture(0)) {
            me.aleksilassila.litematica.printer.config.Configs.Core.WORK_PER_TICK.setIntegerValue(131072);
            f.level.defaultState = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
            var scope = List.of(new PrinterBox(0, 0, 0, 15, 127, 15));
            f.tick(1);
            f.scan("mine", scope, 0, ScanIntent.MINE); // Register the source before real world notifications.
            for (int y = 127; y >= 64; y--) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) f.cache.invalidate(new BlockPos(x, y, z));
                }
            }
            var original = f.scan("mine", scope, 0, ScanIntent.MINE).iterator();
            for (int i = 0; i < 16384; i++) {
                assertTrue(original.hasNext());
                original.next();
            }
            BlockPos retryTarget = new BlockPos(0, 63, 0);
            assertEquals(retryTarget, original.next(), "This delivery comes from the cursor after the notification history fills");
            int reads = f.level.readsAt(retryTarget);
            long dirty = f.engine().dirtyVersion();

            f.engine().reoffer("mine", retryTarget);
            me.aleksilassila.litematica.printer.config.Configs.Core.WORK_PER_TICK.setIntegerValue(256);
            f.tick(2);
            var retry = f.scan("mine", scope, 1, ScanIntent.MINE).iterator();
            assertEquals(retryTarget, retry.next(),
                    "An explicit released delivery must keep its rank even when unrelated notification history is full");
            assertFalse(original.hasNext(), "A new delivery still invalidates the old iterator");
            assertEquals(reads, f.level.readsAt(retryTarget), "History pressure cannot force a fresh world read for this retry");
            assertEquals(dirty, f.engine().dirtyVersion());

            f.cache.invalidate(retryTarget);
            f.tick(3);
            assertEquals(retryTarget.east(), f.scan("mine", scope, 1, ScanIntent.MINE).iterator().next(),
                    "An ordinary notification still yields to the finite cursor when notification history is full");
        } finally {
            me.aleksilassila.litematica.printer.config.Configs.Core.WORK_PER_TICK.setIntegerValue(previousWork);
        }
    }

    @Test void aNewDeliveryIteratorReceivesReoffersWithoutLettingTheOldIteratorConsumeThem() throws Exception {
        try (var f = new ScanIntegrationFixture(2)) {
            f.tick(1);
            var old = f.scan("print", f.boxes, 0, ScanIntent.PRINT).iterator();
            assertEquals(BlockPos.ZERO, old.next());
            int reads = f.level.readsAt(BlockPos.ZERO);
            long dirty = f.engine().dirtyVersion();
            f.engine().reoffer("print", BlockPos.ZERO);

            f.tick(2);
            var currentSource = f.scan("print", f.boxes, 0, ScanIntent.PRINT);
            var current = currentSource.iterator();
            assertFalse(old.hasNext(), "An old iterator cannot consume the next delivery's pending reoffer");
            assertEquals(List.of(BlockPos.ZERO, new BlockPos(1, 0, 0)), drain(current));
            assertTrue(((ScanCandidateIterable) currentSource).isValid(),
                    "A delivery boundary does not invalidate the source guard used by a warm buffer");
            assertEquals(dirty, f.engine().dirtyVersion());
            assertEquals(reads, f.level.readsAt(BlockPos.ZERO));
        }
    }

    @Test void aDeliveryReofferWakesOnlyItsOwnerAndKeepsSharedPositiveFacts() throws Exception {
        int previousWorkload = me.aleksilassila.litematica.printer.config.Configs.Core.WORK_PER_TICK.getIntegerValue();
        // Three complete sources must fit before testing an owner-specific next-tick reoffer.
        me.aleksilassila.litematica.printer.config.Configs.Core.WORK_PER_TICK.setIntegerValue(8192);
        try (var f = new ScanIntegrationFixture(1)) {
            f.tick(1);
            for (String owner : List.of("print", "print_sorted", "print_sorted_other")) {
                assertEquals(List.of(BlockPos.ZERO), ScanIntegrationFixture.drain(
                        f.scan(owner, f.boxes, 0, ScanIntent.PRINT)));
            }
            int reads = f.level.readsAt(BlockPos.ZERO);
            long dirty = f.engine().dirtyVersion();
            f.engine().reoffer("print_sorted", BlockPos.ZERO);
            f.tick(2);
            assertEquals(List.of(BlockPos.ZERO), ScanIntegrationFixture.drain(
                    f.scan("print_sorted", f.boxes, 0, ScanIntent.PRINT)));
            for (String owner : List.of("print", "print_sorted_other")) {
                assertTrue(ScanIntegrationFixture.drain(f.scan(owner, f.boxes, 0, ScanIntent.PRINT)).isEmpty(),
                        "Delivery ownership does not wake another owner sharing the same world facts");
            }
            assertEquals(dirty, f.engine().dirtyVersion());
            assertEquals(reads, f.level.readsAt(BlockPos.ZERO));
        } finally {
            me.aleksilassila.litematica.printer.config.Configs.Core.WORK_PER_TICK.setIntegerValue(previousWorkload);
        }
    }

    @Test void anEvictedRetryIsAutomaticallyReofferedNextTickAfterTheSourceHasCompleted() throws Exception {
        try (var f = new ScanIntegrationFixture(1536)) {
            var queue = new SortedSchematicTargetQueue(f.engine());
            var targets = new PrintTargetScheduler(queue, () -> { });
            targets.setSortedMode(true);
            List<PrinterBox> boxes = List.of(new PrinterBox(0, 0, 0, 15, 7, 15));
            List<BlockPos> delivered = new ArrayList<>();
            long tick = 1;
            do {
                f.tick(tick++);
                delivered.addAll(ScanIntegrationFixture.drain(
                        targets.sortedPositions(boxes, f.level, f.schematic, f.player, 0)));
                assertTrue(tick < 20, "Finish the finite original source before any repair deadline");
            } while (queue.hasPendingWork());
            assertEquals(1536, delivered.size());
            assertEquals(BlockPos.ZERO, delivered.get(0));
            assertTrue(f.cache.metricsFor("print").completedPasses() > 0,
                    "The evicted target must already lie behind a completed source cursor");
            int reads = f.level.readsAt(BlockPos.ZERO);
            long dirty = f.engine().dirtyVersion();

            for (BlockPos pos : delivered.subList(0, 1025)) {
                targets.onResult(pos, PrintPlacementResult.deferred(false), false);
            }
            assertEquals(1024, targets.retainedTargets().size());
            assertFalse(targets.retainedTargets().contains(BlockPos.ZERO));
            for (BlockPos pos : delivered.subList(1, delivered.size())) {
                targets.onResult(pos, new PrintPlacementResult(true, false,
                        PrintPlacementResult.TaskEvent.SUCCESS, -1), false);
            }
            assertTrue(targets.retainedTargets().isEmpty());

            f.tick(tick);
            var fresh = targets.sortedPositions(boxes, f.level, f.schematic, f.player, 0);
            assertEquals(List.of(BlockPos.ZERO), ScanIntegrationFixture.drain(targets.mergeOrdinaryTargets(fresh)),
                    "Retry ownership eviction must reoffer cached work without a reset, dirty event, inventory change or repair delay");
            assertEquals(dirty, f.engine().dirtyVersion());
            assertEquals(reads, f.level.readsAt(BlockPos.ZERO), "A delivery retry reuses the positive world fact");
            assertTrue(targets.retainedTargets().size() <= 1024);
        }
    }

    @Test void transientPrintRetriesAreBoundedAndEvictionDoesNotCompleteTheCachedSource() throws Exception {
        try (var f = new ScanIntegrationFixture(1536)) {
            var queue = new SortedSchematicTargetQueue(f.engine());
            var targets = new PrintTargetScheduler(queue, () -> { });
            targets.setSortedMode(true);
            List<PrinterBox> boxes = List.of(new PrinterBox(0, 0, 0, 15, 7, 15));
            int offered = 0;
            List<BlockPos> delivered = new ArrayList<>();
            long tick = 1;
            do {
                f.tick(tick++);
                var fresh = targets.sortedPositions(boxes, f.level, f.schematic, f.player, 0);
                for (BlockPos pos : ScanIntegrationFixture.drain(fresh)) {
                    delivered.add(pos);
                    offered++;
                }
                assertTrue(tick < 30);
            } while (queue.hasPendingWork());
            assertEquals(1536, offered);
            for (BlockPos pos : delivered) targets.onResult(pos, PrintPlacementResult.deferred(false), false);
            assertTrue(targets.retainedTargets().size() <= 1024, "Transient failure cannot retain the whole blueprint as retries");
            assertFalse(targets.retainedTargets().contains(BlockPos.ZERO));
            int reads = f.level.readsAt(BlockPos.ZERO);
            long dirty = f.engine().dirtyVersion();

            f.engine().resetOwner("print_sorted");
            f.tick(tick);
            var fresh = targets.sortedPositions(boxes, f.level, f.schematic, f.player, 0);
            var recovered = targets.mergeOrdinaryTargets(fresh).iterator();

            assertTrue(recovered.hasNext());
            assertEquals(BlockPos.ZERO, recovered.next(), "Owner wakeup must reoffer the evicted original rank");
            assertEquals(dirty, f.engine().dirtyVersion());
            assertEquals(reads, f.level.readsAt(BlockPos.ZERO), "Eviction must retain the shared positive classification");
            assertTrue(targets.retainedTargets().size() <= 1024);
        }
    }

    private static List<BlockPos> drain(java.util.Iterator<BlockPos> source) {
        List<BlockPos> result = new ArrayList<>();
        source.forEachRemaining(result::add);
        return result;
    }
}
//#endif
