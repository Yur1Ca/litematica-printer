package me.aleksilassila.litematica.printer.handler.handlers;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThirdReviewTrenchCapacityTest {
    @Test void aFullUnreachablePrefixAllowsReachableTargetsBehindItWithoutGrowingTheQueue() throws Exception {
        TrenchFillState state = fullState();
        Predicate<BlockPos> reachable = pos -> pos.getX() >= 2000;
        assertEquals(64, allowance(state, 1L, reachable, pos -> false));
        assertEquals(1024, state.targetCount(), "Admission alone must not discard targets");
        retain(state, positions(2000, 64), 1L, reachable, pos -> false);
        assertTrue(state.hasFillTarget(new BlockPos(2000, 0, 0)));
        assertEquals(64, state.readyFillTargets(1L, pos -> true, reachable).size());
        assertEquals(1024, state.targetCount());
        assertEquals(0, allowance(state, 1L, reachable, pos -> false), "Reclamation is capped across batches in the same tick");
        assertEquals(64, allowance(state, 2L, reachable, pos -> false));
    }

    @Test void reclaimingColdTargetsProtectsQueuedInFlightAndActiveOwnership() throws Exception {
        TrenchFillState state = fullState();
        BlockPos queued = new BlockPos(0, 0, 0);
        BlockPos sent = new BlockPos(1, 0, 0);
        BlockPos active = new BlockPos(2, 0, 0);
        Object attempt = state.reserveQueued(queued, 1L);
        state.markInFlight(sent, 1L, true);
        Predicate<BlockPos> protectedTarget = active::equals;
        assertEquals(64, allowance(state, 1L, pos -> false, protectedTarget));
        retain(state, positions(2000, 64), 1L, pos -> false, protectedTarget);
        assertTrue(state.hasFillTarget(queued));
        assertTrue(state.hasFillTarget(sent));
        assertTrue(state.hasFillTarget(active));
        assertFalse(state.hasFillTarget(new BlockPos(3, 0, 0)));
        assertFalse(state.canAttempt(queued, 2L));
        assertFalse(state.canAttempt(sent, 2L));
        state.completeQueued(queued, attempt, 2L, false);
        assertTrue(state.canAttempt(queued, 4L), "The existing completion token must still own its queued request");
        assertEquals(1024, state.targetCount());
    }

    @Test void readyTargetsBackpressureButDeferredTargetsCanMakeRoom() throws Exception {
        TrenchFillState state = fullState();
        assertEquals(0, allowance(state, 1L, pos -> true, pos -> false));
        for (BlockPos pos : positions(0, 80)) state.deferForMaterial(pos, 21L);
        assertEquals(64, allowance(state, 1L, pos -> true, pos -> false));
        retain(state, positions(2000, 64), 1L, pos -> true, pos -> false);
        assertTrue(state.hasFillTarget(new BlockPos(100, 0, 0)), "An untried ready target must not be evicted");
        assertTrue(state.hasFillTarget(new BlockPos(2000, 0, 0)));
        assertEquals(1024, state.targetCount());
    }

    private static TrenchFillState fullState() {
        TrenchFillState state = new TrenchFillState();
        state.refreshScope(List.of(new PrinterBox(0, 0, 0, 4095, 0, 0)), 1L, pos -> true, pos -> false);
        state.retainBatch(positions(0, 1024), Set.of());
        return state;
    }

    private static Set<BlockPos> positions(int start, int count) {
        Set<BlockPos> result = new LinkedHashSet<>();
        for (int x = start; x < start + count; x++) result.add(new BlockPos(x, 0, 0));
        return result;
    }

    private static int allowance(TrenchFillState state, long now, Predicate<BlockPos> reachable,
                                 Predicate<BlockPos> protectedTarget) throws Exception {
        return state.discoveryAllowance(now, reachable, protectedTarget);
    }

    private static void retain(TrenchFillState state, Set<BlockPos> discovered, long now,
                               Predicate<BlockPos> reachable, Predicate<BlockPos> protectedTarget) throws Exception {
        state.retainBatch(discovered, Set.of(), now, reachable, protectedTarget);
    }
}
