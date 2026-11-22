package me.aleksilassila.litematica.printer.integration.containersync;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static me.aleksilassila.litematica.printer.integration.containersync.ContainerSyncPlanTest.*;
import static org.junit.jupiter.api.Assertions.*;

class ContainerSyncBatchTransferTest {
    @BeforeAll static void setup() { bootstrap(); }

    private static class Port extends ContainerSyncTransferTest.Port implements ContainerSyncTransfer.BatchPort {
        final List<List<ContainerSyncPlan.Step>> batches = new ArrayList<>();
        boolean fenceComplete;
        int protectedSlot = -1;

        public boolean canSendBatch(List<ContainerSyncPlan.Step> steps) {
            return steps.stream().noneMatch(step -> step.slot() == protectedSlot || step.returnSlot() == protectedSlot);
        }

        public BooleanSupplier sendBatch(List<ContainerSyncPlan.Step> steps) {
            fenceComplete = false;
            batches.add(List.copyOf(steps));
            return () -> fenceComplete;
        }
    }

    @Test void aMultiSlotBatchWaitsForTheLastSnapshotAndItsOrderedMarker() {
        var port = new Port();
        var plan = ContainerSyncTransferTest.plan();
        var transfer = new ContainerSyncTransfer(port, plan);
        transfer.tick(0, true);
        assertEquals(List.of(plan.steps()), port.batches);
        var first = plan.steps().getFirst();
        var end = plan.steps().getLast();
        transfer.observe(port.context, first.items(), first.cursor());
        transfer.tick(8000, true);
        assertFalse(transfer.finished());
        assertFalse(port.acquire("other"));
        transfer.observe(port.context, end.items(), end.cursor());
        transfer.tick(9000, true);
        assertFalse(transfer.completed(), "The snapshot alone must not acknowledge a pending batch");
        port.fenceComplete = true;
        transfer.tick(9100, true);
        transfer.tick(9200, true);
        assertTrue(transfer.completed());
        assertTrue(port.clicks.isEmpty(), "No sequential or recovery click");
        assertEquals(1, port.batches.size());
    }

    @Test void lateCorrectionWithForeignCursorOverridesAnEarlierMatchingSnapshot() {
        var port = new Port();
        var plan = ContainerSyncTransferTest.plan();
        var transfer = new ContainerSyncTransfer(port, plan);
        transfer.tick(0, true);
        var end = plan.steps().getLast();
        transfer.observe(port.context, end.items(), end.cursor());
        transfer.observe(port.context, plan.initial(), stone(5));
        port.fenceComplete = true;
        transfer.tick(1, true);
        assertTrue(transfer.needsAttention());
        assertFalse(transfer.finished());
        assertFalse(port.acquire("other"));
        assertFalse(transfer.blockManualClick(false));
        assertFalse(transfer.blockManualClick(true));
        transfer.observe(port.context, plan.initial(), ItemStack.EMPTY);
        assertFalse(transfer.finished(), "A stale snapshot before the user's click is not its confirmation");
        transfer.tick(2, false);
        assertTrue(port.batches.getLast().isEmpty(), "Manual recovery sends only a full query and marker");
        transfer.observe(port.context, plan.initial(), ItemStack.EMPTY);
        assertTrue(transfer.blockManualClick(true));
        transfer.tick(3, false);
        assertFalse(transfer.finished());
        port.fenceComplete = true;
        transfer.tick(4, false);
        assertTrue(transfer.finished());
        assertFalse(transfer.completed());
        assertTrue(port.clicks.isEmpty());
    }

    @Test void aLongMoveIsBoundedAndCancellationReturnsOnlyTheConfirmedRemainder() {
        var port = new Port();
        var plan = ContainerSyncPlan.create(List.of(stone(29)), List.of(ItemStack.EMPTY, stone(64)), RULES);
        var transfer = new ContainerSyncTransfer(port, plan);
        transfer.tick(0, true);
        var firstBatch = port.batches.getFirst();
        assertEquals(16, firstBatch.size());
        var end = firstBatch.getLast();
        transfer.cancel();
        port.fenceComplete = true;
        transfer.tick(2, false);
        assertTrue(port.clicks.isEmpty(), "The marker alone cannot settle the cursor");
        transfer.observe(port.context, end.items(), end.cursor());
        transfer.tick(3, false);
        transfer.tick(4, false);
        assertEquals(2, port.batches.size());
        assertEquals(1, port.batches.getLast().size(), "Only return the remainder to the source");
        assertEquals(1, port.batches.getLast().getFirst().slot());
        transfer.observe(port.context, List.of(stone(15), stone(49)), ItemStack.EMPTY);
        transfer.tick(5, false);
        assertFalse(transfer.finished(), "The recovery itself has an ordered marker");
        port.fenceComplete = true;
        transfer.tick(6, false);
        assertTrue(transfer.finished());
        assertFalse(transfer.completed());
    }

    @Test void aLongMoveContinuesOnlyAfterAnAuthoritativeFirstBatch() {
        var port = new Port();
        var plan = ContainerSyncPlan.create(List.of(stone(29)), List.of(ItemStack.EMPTY, stone(64)), RULES);
        var transfer = new ContainerSyncTransfer(port, plan);
        transfer.tick(0, true);
        var middle = port.batches.getFirst().getLast();
        transfer.observe(port.context, middle.items(), middle.cursor());
        transfer.tick(7000, true);
        assertEquals(1, port.batches.size());
        port.fenceComplete = true;
        transfer.tick(7100, true);
        assertEquals(plan.steps().subList(16, plan.steps().size()), port.batches.getLast());
        var end = port.batches.getLast().getLast();
        transfer.observe(port.context, end.items(), end.cursor());
        port.fenceComplete = true;
        transfer.tick(7200, true);
        assertTrue(transfer.completed());
        assertTrue(port.clicks.isEmpty());
    }

    @Test void preflightChangeNeverTurnsAForeignSlotUpdateIntoACompletedPickup() {
        var port = new Port();
        var plan = ContainerSyncTransferTest.plan();
        var transfer = new ContainerSyncTransfer(port, plan);
        port.unchanged = false;
        transfer.tick(0, true);
        assertEquals(List.of(List.of()), port.batches);
        var foreign = plan.steps().getFirst();
        transfer.observe(port.context, foreign.items(), foreign.cursor());
        port.fenceComplete = true;
        transfer.tick(1, true);
        assertTrue(transfer.needsAttention());
        assertTrue(port.clicks.isEmpty());
        assertEquals(1, port.batches.size());
    }

    @Test void changingTheHeldSlotStopsTheNextMoveAndOnlyReturnsTheConfirmedRemainder() {
        var port = new Port();
        var plan = ContainerSyncPlan.create(List.of(stone(35)), List.of(stone(64), ItemStack.EMPTY), RULES);
        var transfer = new ContainerSyncTransfer(port, plan);
        transfer.tick(0, true);
        var end = port.batches.getFirst().getLast();
        assertFalse(end.cursor().isEmpty());
        port.protectedSlot = 1; // The planned surplus destination became the player's new main hand.
        transfer.observe(port.context, end.items(), end.cursor());
        transfer.tick(1, true);
        assertEquals(1, port.batches.size());
        port.fenceComplete = true;
        transfer.tick(2, true);
        transfer.tick(3, true);
        assertEquals(2, port.batches.size());
        var recovery = port.batches.getLast().getFirst();
        assertEquals(0, recovery.slot());
        assertEquals(1, port.batches.getLast().size());
        transfer.observe(port.context, recovery.items(), recovery.cursor());
        port.fenceComplete = true;
        transfer.tick(4, true);
        assertTrue(transfer.finished());
        assertEquals("SERVER_CHANGED", transfer.problem(), "Pause before another target can be opened");
        assertFalse(transfer.completed());
    }

    @Test void aNewlySelectedSourceIsProtectedEvenDuringCursorRecovery() {
        var port = new Port();
        var plan = ContainerSyncPlan.create(List.of(stone(29)), List.of(ItemStack.EMPTY, stone(64)), RULES);
        var transfer = new ContainerSyncTransfer(port, plan);
        transfer.tick(0, true);
        var end = port.batches.getFirst().getLast();
        port.protectedSlot = 1;
        transfer.observe(port.context, end.items(), end.cursor());
        port.fenceComplete = true;
        transfer.tick(1, true);
        transfer.tick(2, true);
        assertTrue(transfer.needsAttention());
        assertFalse(transfer.finished());
        assertEquals(1, port.batches.size());
        assertTrue(port.clicks.isEmpty());
    }

    @Test void aNewlyChosenUnrelatedSlotDoesNotInterruptTheBatch() {
        var port = new Port();
        var plan = ContainerSyncTransferTest.plan();
        var transfer = new ContainerSyncTransfer(port, plan);
        port.protectedSlot = 3;
        transfer.tick(0, true);
        var end = port.batches.getFirst().getLast();
        port.protectedSlot = 1; // All clicks were sent before this selection change.
        transfer.observe(port.context, end.items(), end.cursor());
        port.fenceComplete = true;
        transfer.tick(1, true);
        assertTrue(transfer.completed());
        assertEquals(1, port.batches.size());
    }

    //#if MC == 260100
    @Test void preflightMapsTheCurrentInventorySlotToTheActualOpenMenu() throws Exception {
        try (var f = new ContainerSyncCrafterTest.CrafterFixture(false)) {
            f.ready();
            var transfer = ContainerSyncTypesTest.field(f.controller, "transfer");
            var port = (ContainerSyncTransfer.BatchPort) ContainerSyncTypesTest.field(transfer, "port");
            // Crafter indices 0..8 belong to the block, 36..44 belong to the player's hotbar.
            var batch = List.of(new ContainerSyncPlan.Step(1, 0, 37, List.of(), ItemStack.EMPTY));
            f.player.inventory.setSelectedSlot(0);
            assertTrue(port.canSendBatch(batch));
            f.player.inventory.setSelectedSlot(1);
            assertFalse(port.canSendBatch(batch));
            assertFalse(port.canSendBatch(List.of(new ContainerSyncPlan.Step(37, 0, 0, List.of(), ItemStack.EMPTY))));
        }
    }
    //#endif
}
