package me.aleksilassila.litematica.printer.utils;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BreakQueueCancellationTest {
    @Test void stoppingProductionKeepsDelayedDestroyMarkersUntilObservation() {
        var state = new BreakQueueState();
        var pending = new BlockPos(1, 2, 3);
        state.add(BlockPos.ZERO);
        state.activePos(BlockPos.ZERO);
        state.markPendingBroken(pending, 100);
        state.clearIfDisabled(false);
        assertFalse(state.hasWork());
        assertTrue(state.isRecentlyBroken(pending), "disable must not forget an unconfirmed destroy");
        state.confirmServerBlockUpdate(pending);
        assertFalse(state.isRecentlyBroken(pending));
    }
}
