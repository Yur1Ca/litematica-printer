package me.aleksilassila.litematica.printer.integration.containersync;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import static me.aleksilassila.litematica.printer.integration.containersync.ContainerSyncPlanTest.*;
import static me.aleksilassila.litematica.printer.integration.containersync.ContainerSyncPickupPlanTest.halfPlan;
import static org.junit.jupiter.api.Assertions.*;

class ContainerSyncHalfPickupTransferTest {
    @BeforeAll static void setup() { bootstrap(); }

    static class ServerPort extends ContainerSyncTransferTest.Port {
        final ContainerSyncPickupSemantics server = new ContainerSyncPickupSemantics(halfPlan().initial(), RULES);
        @Override public void click(int slot, int button) {
            super.click(slot, button);
            server.click(slot, button);
        }
        @Override public boolean unchanged(List<ItemStack> items, ItemStack cursor) {
            return ContainerSyncPlan.matches(items, server.items) && ItemStack.matches(cursor, server.cursor);
        }
        void acknowledge(ContainerSyncTransfer transfer) {
            transfer.observe(context(), server.items, server.cursor);
        }
    }

    @Test void highLatencyHalfPickupDoesNotAdvanceOrResendBeforeAnAuthoritativeSnapshot() {
        var port = new ServerPort(); var transfer = new ContainerSyncTransfer(port, halfPlan());
        transfer.tick(0, true);
        for (int second = 1; second <= 100; second++) transfer.tick(second * 1000L, true);
        assertEquals(List.of(1), port.clicks);
        assertEquals(32, port.server.cursor.getCount());
        assertFalse(port.acquire("quick-shulker"));
        transfer.observe(new Object(), port.server.items, port.server.cursor);
        transfer.tick(100001, true);
        assertEquals(List.of(1), port.clicks);
        port.acknowledge(transfer);
        transfer.tick(100002, true);
        assertEquals(List.of(1, 0), port.clicks);
        assertFalse(transfer.finished());
        port.acknowledge(transfer);
        transfer.tick(100003, true);
        assertTrue(transfer.completed());
        assertTrue(port.acquire("quick-shulker"));
        assertEquals(0, port.refreshes);
    }

    @Test void disablingAfterHalfPickupMergesTheCursorIntoTheNonemptyOrigin() {
        var port = new ServerPort(); var transfer = new ContainerSyncTransfer(port, halfPlan());
        transfer.tick(0, true);
        transfer.tick(1, false);
        port.acknowledge(transfer);
        transfer.tick(2, false);
        assertEquals(List.of(1, 1), port.clicks);
        assertEquals(64, port.server.items.get(1).getCount());
        assertTrue(port.server.cursor.isEmpty());
        for (int second = 1; second <= 100; second++) transfer.tick(1000L * second, false);
        assertFalse(transfer.finished(), "Local/server-side effects cannot release the lease before the reply");
        assertFalse(port.acquire("take-it-out"));
        port.acknowledge(transfer);
        assertTrue(transfer.finished(), "The acknowledgement contains both the remaining source and returned cursor");
        assertFalse(transfer.completed());
        assertTrue(port.acquire("take-it-out"));
        assertEquals(0, port.refreshes);
    }

    @Test void rejectedHalfInsertionCanReturnTheOwnedCursorToItsMatchingOrigin() {
        var port = new ServerPort(); var transfer = new ContainerSyncTransfer(port, halfPlan());
        transfer.tick(0, true); port.acknowledge(transfer); transfer.tick(1, true);
        port.server.items.set(0, ItemStack.EMPTY);
        port.server.cursor = stone(32); // Server rejected only the destination insertion.
        port.acknowledge(transfer);
        transfer.tick(6000, true);
        assertEquals(List.of(1, 0, 1), port.clicks);
        assertEquals(64, port.server.items.get(1).getCount());
        assertTrue(port.server.cursor.isEmpty());
        assertFalse(port.acquire("printer"));
        port.acknowledge(transfer);
        assertTrue(transfer.finished());
        assertNotNull(transfer.problem());
        assertTrue(port.acquire("printer"));
    }

    @Test void aFullOrForeignOriginRequiresManualRecoveryWithoutOverwritingIt() {
        for (ItemStack changed : List.of(stone(40), new ItemStack(Items.DIRT, 1))) {
            var port = new ServerPort(); var transfer = new ContainerSyncTransfer(port, halfPlan());
            transfer.tick(0, true); port.acknowledge(transfer); transfer.tick(1, true);
            port.server.items.set(0, ItemStack.EMPTY);
            port.server.items.set(1, changed.copy());
            port.server.cursor = stone(32);
            port.acknowledge(transfer); transfer.tick(6000, false);
            assertTrue(transfer.needsAttention());
            assertEquals(List.of(1, 0), port.clicks);
            assertTrue(ItemStack.matches(changed, port.server.items.get(1)));
            assertFalse(port.acquire("printer"));
        }
    }

    @Test void changingSessionDuringHalfPickupNeverSendsRecoveryToTheNewSession() {
        var port = new ServerPort(); var transfer = new ContainerSyncTransfer(port, halfPlan());
        Object old = port.context;
        transfer.tick(0, true);
        port.context = new Object();
        transfer.observe(old, port.server.items, port.server.cursor);
        transfer.tick(6000, false);
        assertTrue(transfer.finished());
        assertEquals(List.of(1), port.clicks);
        assertTrue(port.acquire("printer"));
    }

    @Test void disablingWithHalfInsertionInFlightWaitsForItsEmptyCursorReply() {
        var port = new ServerPort(); var transfer = new ContainerSyncTransfer(port, halfPlan());
        transfer.tick(0, true); port.acknowledge(transfer); transfer.tick(1, true);
        transfer.tick(6000, false);
        assertFalse(port.acquire("printer"));
        assertEquals(List.of(1, 0), port.clicks);
        port.acknowledge(transfer); transfer.tick(6001, false);
        assertTrue(transfer.finished());
        assertTrue(port.server.cursor.isEmpty());
        assertEquals(32, port.server.items.get(0).getCount());
        assertEquals(32, port.server.items.get(1).getCount());
        assertEquals(List.of(1, 0), port.clicks);
    }
}
