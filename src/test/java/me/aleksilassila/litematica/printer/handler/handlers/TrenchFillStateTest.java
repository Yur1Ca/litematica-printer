package me.aleksilassila.litematica.printer.handler.handlers;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrenchFillStateTest {
    @Test
    void retryAndWorldUpdateControlFillReadiness() {
        TrenchFillState state = new TrenchFillState();
        BlockPos pos = new BlockPos(1, 2, 3);
        state.updateTargets(List.of(), Set.of(pos), Set.of(), 10L, ignored -> true);

        state.defer(pos, 15L);
        assertFalse(state.canAttempt(pos, 14L));
        assertTrue(state.canAttempt(pos, 15L));

        state.markInFlight(pos, 15L, true);
        assertFalse(state.canAttempt(pos, 16L));
        state.onBlockUpdated(pos, false);
        assertFalse(state.hasWork());
    }

    @Test
    void resetDropsStaleTargetsAndRateHistory() {
        TrenchFillState state = new TrenchFillState();
        BlockPos pos = new BlockPos(1, 2, 3);
        state.updateTargets(List.of(), Set.of(pos), Set.of(), 10L, ignored -> true);
        state.markInFlight(pos, 10L, true);
        assertEquals(1, state.sentThisTick());

        state.clear();

        assertFalse(state.hasWork());
        assertEquals(0, state.sentThisTick());
        assertEquals(Long.MIN_VALUE, state.lastSentTick());
    }
}
