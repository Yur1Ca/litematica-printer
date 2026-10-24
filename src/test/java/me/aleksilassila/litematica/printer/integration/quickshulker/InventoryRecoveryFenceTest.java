package me.aleksilassila.litematica.printer.integration.quickshulker;

import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryRecoveryFenceTest {
    private static ServerboundClientCommandPacket request() {
        return new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS);
    }

    @Test void priorUserStatsRepliesDoNotAcknowledgeOurRequest() {
        Object connection = new Object();
        InventoryRecoveryFence.sent(connection, request());
        var ticket = InventoryRecoveryFence.prepare(connection);
        assertFalse(ticket.complete());
        InventoryRecoveryFence.sent(connection, ticket.packet());
        InventoryRecoveryFence.received(connection);
        assertFalse(ticket.complete());
        InventoryRecoveryFence.received(connection);
        assertTrue(ticket.complete());
        assertFalse(ticket.invalidated());
    }

    @Test void inputsEnqueuedAfterPrepareAreNotCoveredByALaterMarkerWrite() {
        Object connection = new Object();
        var ticket = InventoryRecoveryFence.prepare(connection);
        InventoryRecoveryFence.invalidate(connection);
        InventoryRecoveryFence.sent(connection, ticket.packet());
        InventoryRecoveryFence.received(connection);
        assertTrue(ticket.complete());
        assertTrue(ticket.invalidated());
    }

    @Test void replacingATicketOnTheSameConnectionRetainsOutstandingReplyCounts() {
        Object connection = new Object();
        var old = InventoryRecoveryFence.prepare(connection);
        InventoryRecoveryFence.sent(connection, old.packet());
        var fresh = InventoryRecoveryFence.prepare(connection);
        InventoryRecoveryFence.sent(connection, fresh.packet());
        InventoryRecoveryFence.received(connection);
        assertFalse(fresh.complete());
        InventoryRecoveryFence.received(connection);
        assertTrue(fresh.complete());
    }

    @Test void responsesFromAnotherServerCannotAcknowledgeCurrentRecovery() {
        Object oldConnection = new Object(), freshConnection = new Object();
        var old = InventoryRecoveryFence.prepare(oldConnection);
        var fresh = InventoryRecoveryFence.prepare(freshConnection);
        InventoryRecoveryFence.sent(oldConnection, old.packet());
        InventoryRecoveryFence.sent(freshConnection, fresh.packet());
        InventoryRecoveryFence.received(oldConnection);
        assertFalse(fresh.complete());
        InventoryRecoveryFence.received(freshConnection);
        assertTrue(fresh.complete());
    }
}
