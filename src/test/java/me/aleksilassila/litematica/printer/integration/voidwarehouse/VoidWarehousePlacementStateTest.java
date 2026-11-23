package me.aleksilassila.litematica.printer.integration.voidwarehouse;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class VoidWarehousePlacementStateTest {
    private static final List<String> STONE = List.of("minecraft:stone");

    @Test
    void onlyTheRejectedIceRequestAllowsOneFinalPlacementDuringItsBackoff() {
        var state = new VoidWarehousePlacementState<String>();
        enable(state);
        var ice = List.of("minecraft:ice");
        var stairs = List.of("minecraft:quartz_stairs");
        var first = state.beginRequest(ice, "a", 0);
        var other = state.beginRequest(ice, "b", 0);
        var token = new AtomicInteger();
        state.onIceRequestRejected("a", token::set);
        state.receiveResult(first, 3, 1);
        state.receiveResult(other, 3, 1);
        assertEquals(first, token.get());
        assertFalse(state.isTargetAvailable("a", 2));
        assertTrue(state.isTargetAvailable("a", 2, first));
        assertFalse(state.canUseInventoryFallback("a", 2));
        assertEquals(0, state.beginRequest(stairs, "b", 2, first));
        assertEquals(0, state.beginRequest(stairs, "a", 2, other));
        assertEquals(0, state.beginRequest(ice, "a", 2));

        int throttle = state.beginRequest(STONE, "c", 2);
        state.receiveResult(throttle, 4, 3);
        assertEquals(0, state.beginRequest(stairs, "a", 7, first));
        int finish = state.beginRequest(stairs, "a", 8, first);
        assertNotEquals(0, finish);
        assertEquals(0, state.beginRequest(stairs, "a", 8, first));
        state.receiveResult(finish, 3, 9);
        assertFalse(state.canRequest(stairs, "a", 28, first));
        assertFalse(state.canRequest(stairs, "a", 28, finish));
        assertTrue(state.canRequest(stairs, "a", 29));
    }

    @Test
    void aSourceThatReturnsToFlowDuringTheFinalRequestKeepsTheOriginalIceDelay() {
        var state = new VoidWarehousePlacementState<String>();
        enable(state);
        var ice = List.of("minecraft:ice");
        int rejected = state.beginRequest(ice, "a", 0);
        state.onIceRequestRejected("a", nonce -> assertEquals(rejected, nonce));
        state.receiveResult(rejected, 3, 1);
        int finish = state.beginRequest(List.of("minecraft:quartz_stairs"), "a", 4, rejected);
        assertNotEquals(0, finish);
        state.receiveResult(finish, 6, 5);
        assertFalse(state.isTargetAvailable("a", 6));
        assertFalse(state.canUseInventoryFallback("a", 6));
        assertFalse(state.canRequest(ice, "a", 20, rejected));
        assertTrue(state.canRequest(ice, "a", 21));
    }

    @Test
    void staleIceAndReusedCoordinatesNeverInheritAnEarlierRejection() {
        var state = new VoidWarehousePlacementState<String>();
        enable(state);
        var ice = List.of("minecraft:ice");
        int first = state.beginRequest(ice, "a", 0);
        var token = new AtomicInteger(-1);
        state.onIceRequestRejected("a", token::set);
        state.receiveResult(first, 6, 1);
        assertEquals(0, token.get());
        assertEquals(0, state.beginRequest(STONE, "a", 1, first));
        int second = state.beginRequest(ice, "a", 2);
        state.onIceRequestRejected("a", token::set);
        state.receiveResult(second, 3, 3);
        assertEquals(second, token.get());
        state.reset();
        enable(state);
        int replacement = state.beginRequest(STONE, "a", 0);
        state.receiveResult(replacement, 3, 1);
        assertFalse(state.canRequest(STONE, "a", 2, second));
    }

    @Test
    void onlyTheRejectedRequestIsNotifiedOnceAndAfterItsBackoffStarts() {
        for (int status : List.of(3, 6)) {
            var state = new VoidWarehousePlacementState<String>();
            enable(state);
            var notified = new AtomicInteger();
            int nonce = state.beginRequest(STONE, "a", 0);
            int other = state.beginRequest(STONE, "b", 0);
            var first = state.onCurrentRequestRejected("a", () -> {
                assertFalse(state.isPending("a"));
                assertFalse(state.canRequest(STONE, "a", 1));
                notified.incrementAndGet();
            });
            assertNotNull(first);
            assertTrue(first.getAsBoolean());
            assertNull(state.onCurrentRequestRejected("a", () -> fail("replaced first listener")));
            var second = state.onCurrentRequestRejected("b", () -> fail("OK called rejection listener"));
            assertNotNull(second);
            assertTrue(second.getAsBoolean());
            state.receiveResult(other, 0, 1);
            assertFalse(second.getAsBoolean());
            assertTrue(first.getAsBoolean());
            state.receiveResult(nonce, status, 1);
            assertFalse(first.getAsBoolean());
            state.receiveResult(nonce, status, 1);
            assertEquals(1, notified.get());
            assertTrue(state.canRequest(STONE, "a", status == 6 ? 2 : 21));
        }
    }

    @Test
    void cancellationExpiryAndResetNeverNotifyAnOldRequestOrItsReplacement() {
        var state = new VoidWarehousePlacementState<String>();
        enable(state);
        Runnable unexpected = () -> fail("abandoned or replacement request was notified");
        assertNull(state.onCurrentRequestRejected("a", unexpected));
        int cancelled = state.beginRequest(STONE, "a", 0);
        var first = state.onCurrentRequestRejected("a", unexpected);
        assertNotNull(first);
        assertTrue(first.getAsBoolean());
        state.cancel(cancelled);
        assertFalse(first.getAsBoolean());
        int expired = state.beginRequest(STONE, "a", 0);
        var second = state.onCurrentRequestRejected("a", unexpected);
        assertNotNull(second);
        assertFalse(first.getAsBoolean());
        state.tick(99);
        assertTrue(second.getAsBoolean());
        state.tick(100);
        assertFalse(second.getAsBoolean());
        int beforeReset = state.beginRequest(STONE, "a", 100);
        var oldWorld = state.onCurrentRequestRejected("a", unexpected);
        assertNotNull(oldWorld);
        assertTrue(oldWorld.getAsBoolean());
        state.reset();
        assertFalse(oldWorld.getAsBoolean());
        enable(state);
        int current = state.beginRequest(STONE, "a", 0);
        var replacement = state.onCurrentRequestRejected("a", unexpected);
        assertNotNull(replacement);
        for (int abandoned : List.of(cancelled, expired, beforeReset)) {
            state.receiveResult(abandoned, 3, 1);
        }
        assertFalse(oldWorld.getAsBoolean());
        assertTrue(replacement.getAsBoolean());
        state.receiveResult(current, 0, 1);
        assertFalse(replacement.getAsBoolean());
        assertTrue(state.canRequest(STONE, "a", 1));
    }

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
