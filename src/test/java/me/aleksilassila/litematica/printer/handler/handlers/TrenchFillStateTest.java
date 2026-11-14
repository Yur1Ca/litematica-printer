package me.aleksilassila.litematica.printer.handler.handlers;

import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrenchFillStateTest {
    @Test void partialDiscoveryKeepsKnownTargetsAndBackpressuresBeforeRetainingTheWholeSelection() {
        TrenchFillState state = new TrenchFillState();
        var scope = List.of(new PrinterBox(0, 0, 0, 2047, 0, 0));
        state.refreshScope(scope, 1L, pos -> true, pos -> false);
        java.util.LinkedHashSet<BlockPos> discovered = new java.util.LinkedHashSet<>();
        for (int x = 0; x < 2048; x++) discovered.add(new BlockPos(x, 0, 0));
        state.retainBatch(discovered, Set.of());
        assertEquals(1024, state.targetCount());
        assertEquals(0, state.remainingCapacity());
        state.refreshScope(scope, 2L, pos -> true, pos -> false);
        assertTrue(state.hasFillTarget(BlockPos.ZERO), "An unfinished next slice cannot clear a retained target");
        state.removeFillTarget(BlockPos.ZERO);
        assertEquals(1, state.remainingCapacity());
        BlockPos next = new BlockPos(1024, 0, 0);
        state.retainBatch(Set.of(next), Set.of());
        assertTrue(state.hasFillTarget(next));
        assertEquals(1024, state.targetCount());
    }

    @Test
    void delayedWorldConfirmationKeepsTheTargetInFlight() {
        TrenchFillState state = new TrenchFillState();
        BlockPos pos = new BlockPos(1, 2, 3);
        state.updateTargets(List.of(), Set.of(pos), Set.of(), 10L, ignored -> true);
        state.markInFlight(pos, 10L, true);
        for (long tick = 11L; tick <= 50L; tick++) {
            state.beginTick(tick);
            state.updateTargets(List.of(), Set.of(pos), Set.of(), tick, ignored -> true);
            assertTrue(state.hasWork());
            assertFalse(state.canAttempt(pos, tick), "Do not resend while waiting for the world update");
        }
        state.onBlockUpdated(pos, false);
        assertFalse(state.hasWork());
    }

    @Test
    void rejectedAndTimedOutPlacementsBecomeRetryable() {
        TrenchFillState state = new TrenchFillState();
        BlockPos pos = new BlockPos(1, 2, 3);
        state.updateTargets(List.of(), Set.of(pos), Set.of(), 10L, ignored -> true);
        state.markInFlight(pos, 10L, true);
        state.onBlockUpdated(pos, true);
        assertTrue(state.hasFillTarget(pos));
        assertTrue(state.canAttempt(pos, 11L));
        state.markInFlight(pos, 11L, false);
        state.updateTargets(List.of(), Set.of(pos), Set.of(), 52L, ignored -> true);
        assertTrue(state.canAttempt(pos, 52L));
    }

    @Test
    void resetDiscardsOldWorldRepliesAndAllowsTheNewWorldTarget() {
        TrenchFillState state = new TrenchFillState();
        BlockPos pos = new BlockPos(1, 2, 3);
        state.updateTargets(List.of(), Set.of(pos), Set.of(), 500L, ignored -> true);
        state.markInFlight(pos, 500L, true);
        state.clear();
        state.onBlockUpdated(pos, false);
        assertFalse(state.hasWork());
        state.updateTargets(List.of(), Set.of(pos), Set.of(), 1L, ignored -> true);
        assertTrue(state.canAttempt(pos, 1L));
        assertTrue(state.canSend(1L, 20, 1));
    }

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

    @Test
    void fillBatchRespectsPerTickLimitWithoutSerializingSameTick() {
        TrenchFillState state = new TrenchFillState();

        assertTrue(state.canSend(10L, 2, 3));
        state.markInFlight(new BlockPos(1, 2, 3), 10L, true);
        assertTrue(state.canSend(10L, 2, 3));
        state.markInFlight(new BlockPos(2, 2, 3), 10L, true);
        state.markInFlight(new BlockPos(3, 2, 3), 10L, true);
        assertFalse(state.canSend(10L, 2, 3));

        state.beginTick(11L);
        assertFalse(state.canSend(11L, 2, 3));
        assertTrue(state.canSend(12L, 2, 3));
    }

    @Test
    void distantTrenchTargetsDoNotEnterTheActionBatch() {
        TrenchFillState state = new TrenchFillState();
        BlockPos near = new BlockPos(1, 2, 3);
        BlockPos far = new BlockPos(100, 2, 3);
        state.updateTargets(List.of(), Set.of(near, far), Set.of(far), 10L, ignored -> true);

        assertEquals(List.of(near), state.readyFillTargets(10L, ignored -> true,
                pos -> pos.equals(near)));
        assertEquals(List.of(), state.readyWaterloggedTargets(pos -> pos.equals(near)));
    }

    @Test
    void aQueuedSendBeforePreprocessStillCountsAgainstTheCurrentTickBudget() {
        TrenchFillState state = new TrenchFillState();
        BlockPos first = new BlockPos(1, 2, 3);
        state.markInFlight(first, 100L, true);

        state.beginTick(100L);
        assertEquals(1, state.sentThisTick(), "The scheduler can send before mine preprocess begins");
        assertFalse(state.canSend(100L, 0, 1));
        state.beginTick(100L);
        assertEquals(1, state.sentThisTick(), "Same-tick initialization must be idempotent");

        state.beginTick(101L);
        assertEquals(0, state.sentThisTick());
        assertTrue(state.canSend(101L, 0, 1));
    }

}
