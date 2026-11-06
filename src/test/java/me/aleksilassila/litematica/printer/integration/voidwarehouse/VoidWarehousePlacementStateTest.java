package me.aleksilassila.litematica.printer.integration.voidwarehouse;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VoidWarehousePlacementStateTest {
    private static final List<String> STONE = List.of("minecraft:stone");

    @Test
    void enablingCapabilityWakesBlockedMaterialsOnceWhileTrueRefreshesStayQuiet() {
        var state = new VoidWarehousePlacementState<String>();
        assertFalse(state.consumeMaterialAvailabilityChange());
        state.receiveHello(state.beginHello(0), false, 0);
        assertFalse(state.consumeMaterialAvailabilityChange());
        state.receiveHello(state.beginHello(100), true, 100);
        assertTrue(state.consumeMaterialAvailabilityChange());
        assertFalse(state.consumeMaterialAvailabilityChange());
        state.receiveHello(state.beginHello(200), true, 200);
        assertFalse(state.consumeMaterialAvailabilityChange(), "An unchanged periodic hello must not force a full rescan");
        int request = state.beginRequest(STONE, "a", 201);
        state.receiveResult(request, 1, 201);
        assertFalse(state.consumeMaterialAvailabilityChange());
        state.receiveHello(state.beginHello(300), true, 300);
        assertTrue(state.consumeMaterialAvailabilityChange());
        assertFalse(state.consumeMaterialAvailabilityChange());
    }

    @Test
    void emptyCandidateCooldownExpiryWakesBlockedMaterialsExactlyOnce() {
        var state = new VoidWarehousePlacementState<String>();
        enable(state);
        state.consumeMaterialAvailabilityChange();
        int request = state.beginRequest(STONE, "a", 0);
        state.receiveResult(request, 2, 1);
        assertFalse(state.consumeMaterialAvailabilityChange());
        state.tick(100);
        assertFalse(state.consumeMaterialAvailabilityChange());
        state.tick(101);
        assertTrue(state.consumeMaterialAvailabilityChange());
        assertTrue(state.canRequest(STONE, "b", 101));
        state.tick(102);
        assertFalse(state.consumeMaterialAvailabilityChange());
    }

    @Test
    void rejectedTargetExpiryWakesMaterialsButThrottleExpiryDoesNot() {
        var state = new VoidWarehousePlacementState<String>();
        enable(state);
        state.consumeMaterialAvailabilityChange();
        int rejected = state.beginRequest(STONE, "a", 0);
        state.receiveResult(rejected, 3, 1);
        state.tick(20);
        assertFalse(state.consumeMaterialAvailabilityChange());
        state.tick(21);
        assertTrue(state.consumeMaterialAvailabilityChange());
        assertFalse(state.consumeMaterialAvailabilityChange());
        int throttled = state.beginRequest(STONE, "b", 21);
        state.receiveResult(throttled, 4, 21);
        state.tick(41);
        assertFalse(state.consumeMaterialAvailabilityChange(), "Traffic backoff should not restart material-blocked workflows");
    }

    @Test
    void resetDiscardsUnconsumedMaterialChangesFromTheOldWorld() {
        var state = new VoidWarehousePlacementState<String>();
        int hello = state.beginHello(0);
        state.receiveHello(hello, true, 0);
        state.reset();
        assertFalse(state.consumeMaterialAvailabilityChange());
        state.receiveHello(hello, true, 0);
        assertFalse(state.consumeMaterialAvailabilityChange());
        state.receiveHello(state.beginHello(0), true, 0);
        assertTrue(state.consumeMaterialAvailabilityChange());
    }

    @Test
    void newSessionWaitsForItsFirstMatchingHelloEvenWhenServerDisablesTheRule() {
        var state = new VoidWarehousePlacementState<String>();
        assertTrue(state.isNegotiating(0));
        int hello = state.beginHello(0);
        state.receiveHello(hello + 1, true, 0);
        assertTrue(state.isNegotiating(0), "An unrelated result cannot authorize inventory fallback");
        state.receiveHello(hello, false, 1);
        assertFalse(state.isNegotiating(1));
        assertFalse(state.isEnabled());
        int refresh = state.beginHello(100);
        assertTrue(refresh > 0);
        assertFalse(state.isNegotiating(100), "Periodic refreshes must not pause ordinary printing again");
    }

    @Test
    void firstHelloTimeoutReleasesInventoryFallbackAndDoesNotWaitAgainForRefresh() {
        var state = new VoidWarehousePlacementState<String>();
        int hello = state.beginHello(5);
        assertTrue(state.isNegotiating(104));
        assertFalse(state.isNegotiating(105));
        assertFalse(state.isEnabled());
        state.receiveHello(hello, true, 105);
        assertFalse(state.isEnabled(), "A hello arriving after the timeout cannot enable direct placement");
        int refresh = state.beginHello(105);
        assertTrue(refresh > 0);
        assertFalse(state.isNegotiating(105));
        state.receiveHello(refresh, true, 106);
        assertTrue(state.isEnabled());
        assertFalse(state.isNegotiating(106));
    }

    @Test
    void resettingTheWorldStartsUnknownAgainAndIgnoresTheOldHello() {
        var state = new VoidWarehousePlacementState<String>();
        int oldHello = state.beginHello(0);
        state.receiveHello(oldHello, false, 0);
        assertFalse(state.isNegotiating(0));
        state.reset();
        assertTrue(state.isNegotiating(0));
        state.receiveHello(oldHello, true, 0);
        assertTrue(state.isNegotiating(0));
        assertFalse(state.isEnabled());
        int currentHello = state.beginHello(0);
        state.receiveHello(oldHello, false, 0);
        assertTrue(state.isNegotiating(0));
        state.receiveHello(currentHello, true, 1);
        assertFalse(state.isNegotiating(1));
        assertTrue(state.isEnabled());
    }

    @Test
    void refreshingAnEnabledSessionDoesNotPausePrintingOrDiscardItsConclusion() {
        var state = new VoidWarehousePlacementState<String>();
        enable(state);
        int refresh = state.beginHello(100);
        assertTrue(refresh > 0);
        assertFalse(state.isNegotiating(100));
        assertTrue(state.canRequest(STONE, "a", 100));
        state.tick(200);
        assertFalse(state.isNegotiating(200), "A periodic refresh timeout must not restart initial negotiation");
        assertTrue(state.isEnabled(), "A late capability refresh cannot revoke confirmed placement support");
        assertTrue(state.canRequest(STONE, "a", 200));
    }

    @Test
    void waitsForMatchingHelloAndRenegotiatesAfterOneHundredTicks() {
        var state = new VoidWarehousePlacementState<String>();
        assertFalse(state.isEnabled());
        int hello = state.beginHello(0);
        state.receiveHello(hello + 1, true, 0);
        assertFalse(state.isEnabled());
        state.receiveHello(hello, true, 0);
        assertTrue(state.isEnabled());
        assertEquals(0, state.beginHello(99));
        int next = state.beginHello(100);
        assertNotEquals(0, next);
        state.receiveHello(hello, false, 100);
        assertTrue(state.isEnabled(), "A previous hello cannot overwrite a newer negotiation");
        state.receiveHello(next, false, 100);
        assertFalse(state.isEnabled());
        int recovery = state.beginHello(200);
        state.receiveHello(recovery, true, 200);
        assertTrue(state.isEnabled());
    }

    @Test
    void requiresNegotiationDeduplicatesTargetsAndReleasesAcknowledgedRequests() {
        var state = new VoidWarehousePlacementState<String>();
        assertEquals(0, state.beginRequest(STONE, "a", 0));
        enable(state);
        int request = state.beginRequest(STONE, "a", 0);
        assertTrue(request > 0);
        assertTrue(state.isPending("a"));
        assertFalse(state.canRequest(STONE, "a", 0));
        assertEquals(0, state.beginRequest(STONE, "a", 0));
        state.receiveResult(request, 0, 0);
        assertFalse(state.isPending("a"));
        assertTrue(state.canRequest(STONE, "a", 0));
    }

    @Test
    void limitsBothPerTickTrafficAndTotalInFlightRequests() {
        var state = new VoidWarehousePlacementState<String>();
        enable(state);
        for (int i = 0; i < 32; i++) assertNotEquals(0, state.beginRequest(STONE, "a" + i, 0));
        assertEquals(0, state.beginRequest(STONE, "rate-limited", 0));
        for (int i = 0; i < 32; i++) assertNotEquals(0, state.beginRequest(STONE, "b" + i, 1));
        assertEquals(0, state.beginRequest(STONE, "capacity-limited", 2));
        state.tick(100);
        assertFalse(state.isPending("a0"));
        assertTrue(state.isPending("b0"));
        assertNotEquals(0, state.beginRequest(STONE, "after-timeout", 100));
    }

    @Test
    void lateAcknowledgementCannotReleaseReplacementRequest() {
        var state = new VoidWarehousePlacementState<String>();
        enable(state);
        int expired = state.beginRequest(STONE, "a", 0);
        state.tick(100);
        int replacement = state.beginRequest(STONE, "a", 100);
        assertNotEquals(0, replacement);
        state.receiveResult(expired, 1, 100);
        assertTrue(state.isEnabled(), "A stale RULE_OFF must not disable the current session");
        assertTrue(state.isPending("a"));
        state.receiveResult(replacement, 0, 100);
        assertFalse(state.isPending("a"));
    }

    @Test
    void emptyCoolsOnlyRequestedCandidatesAndKeepsOtherMaterialsAvailable() {
        var state = new VoidWarehousePlacementState<String>();
        enable(state);
        int request = state.beginRequest(STONE, "a", 0);
        state.receiveResult(request, 2, 2);
        assertFalse(state.canRequest(STONE, "b", 101));
        assertEquals(List.of("minecraft:dirt"), state.eligibleCandidates(
                List.of("minecraft:stone", "minecraft:dirt", "minecraft:dirt"), 2));
        assertTrue(state.canRequest(List.of("minecraft:dirt"), "b", 2));
        assertTrue(state.canRequest(STONE, "b", 102));
    }

    @Test
    void rejectionBacksOffTargetAndThrottleAlsoBacksOffAllRequests() {
        var state = new VoidWarehousePlacementState<String>();
        enable(state);
        int rejected = state.beginRequest(STONE, "a", 0);
        state.receiveResult(rejected, 3, 1);
        assertFalse(state.isTargetAvailable("a", 20), "A rejected target waits before retrying direct placement");
        assertFalse(state.canRequest(STONE, "a", 20));
        assertTrue(state.canRequest(STONE, "b", 1));
        assertTrue(state.canRequest(STONE, "a", 21));
        assertTrue(state.isTargetAvailable("a", 21));
        int throttled = state.beginRequest(STONE, "b", 21);
        state.receiveResult(throttled, 4, 21);
        assertTrue(state.isTargetAvailable("b", 21), "Throttle should defer instead of fetching inventory materials");
        assertFalse(state.canRequest(STONE, "c", 25));
        assertTrue(state.canRequest(STONE, "c", 26));
        assertFalse(state.canRequest(STONE, "b", 40));
        assertTrue(state.canRequest(STONE, "b", 41));
    }

    @Test
    void staleSnapshotOnlyDefersUntilTheNextTickAndKeepsMaterialsAvailable() {
        var state = new VoidWarehousePlacementState<String>();
        enable(state);
        int request = state.beginRequest(STONE, "a", 0);
        state.receiveResult(request, 6, 1);
        assertFalse(state.canRequest(STONE, "a", 1));
        assertTrue(state.isEnabled());
        assertEquals(STONE, state.eligibleCandidates(STONE, 1));
        assertTrue(state.canRequest(STONE, "b", 1));
        assertTrue(state.canRequest(STONE, "a", 2), "A changed snapshot needs observation, not a one-second material fallback");
        assertTrue(state.isTargetAvailable("a", 2));
    }

    @Test
    void ruleOffCannotBeOverwrittenByAnEarlierOutstandingHello() {
        var state = new VoidWarehousePlacementState<String>();
        enable(state);
        int request = state.beginRequest(STONE, "a", 100);
        int hello = state.beginHello(100);
        state.receiveResult(request, 1, 101);
        assertFalse(state.isNegotiating(101), "RULE_OFF is a definitive fallback conclusion");
        state.receiveHello(hello, true, 102);
        assertFalse(state.isEnabled());
        assertFalse(state.isNegotiating(102));
        int recovery = state.beginHello(200);
        state.receiveHello(recovery, true, 200);
        assertTrue(state.isEnabled());
    }

    @Test
    void ruleOffImmediatelyDisablesAndDisconnectDropsAllStateWithoutReusingNonces() {
        var state = new VoidWarehousePlacementState<String>();
        enable(state);
        int old = state.beginRequest(STONE, "a", 0);
        state.receiveResult(old, 1, 0);
        assertFalse(state.isEnabled());
        assertEquals(0, state.beginRequest(STONE, "b", 1));
        state.reset();
        assertFalse(state.isPending("a"));
        int hello = state.beginHello(0);
        state.receiveResult(old, 0, 0);
        state.receiveHello(hello - 1, true, 0);
        assertFalse(state.isEnabled());
        state.receiveHello(hello, true, 0);
        int current = state.beginRequest(STONE, "a", 0);
        assertNotEquals(old, current);
        state.receiveResult(old, 2, 0);
        assertTrue(state.isPending("a"));
        state.cancel(current);
        assertTrue(state.canRequest(STONE, "a", 0));
    }

    @Test
    void timeoutAndResetDropCooldownsAndRejectUnusableInput() {
        var state = new VoidWarehousePlacementState<String>();
        enable(state);
        assertFalse(state.canRequest(null, "a", 0));
        assertFalse(state.canRequest(List.of(), "a", 0));
        assertFalse(state.canRequest(STONE, null, 0));
        int request = state.beginRequest(STONE, "a", 0);
        state.receiveResult(request, 2, 1);
        state.reset();
        enable(state);
        assertTrue(state.canRequest(STONE, "a", 0));
        int timedOut = state.beginRequest(STONE, "a", 0);
        state.receiveResult(timedOut, 1, 100);
        assertTrue(state.isEnabled(), "A timeout is not a fresh server rejection");
        assertFalse(state.isPending("a"));
    }

    private static void enable(VoidWarehousePlacementState<String> state) {
        state.receiveHello(state.beginHello(0), true, 0);
    }
}
