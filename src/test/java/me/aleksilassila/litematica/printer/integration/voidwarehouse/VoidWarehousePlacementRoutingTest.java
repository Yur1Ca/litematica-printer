package me.aleksilassila.litematica.printer.integration.voidwarehouse;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VoidWarehousePlacementRoutingTest {
    private static final List<String> MATERIALS = List.of("minecraft:soul_sand", "minecraft:smooth_stone");

    @Test
    void rejectedPlacementWaitsWithoutOrderingAnInventoryStack() {
        assertFailureWaits(3);
    }

    @Test
    void malformedPlacementWaitsWithoutOrderingAnInventoryStack() {
        assertFailureWaits(5);
    }

    @Test
    void staleWorldSnapshotWaitsWithoutOrderingAnInventoryStack() {
        var state = enabledState();
        state.receiveResult(state.beginRequest(MATERIALS, "target", 0), 6, 1);
        assertEquals(VoidWarehousePlacementPolicy.Route.WAIT, route(state, "target", 1));
        assertEquals(VoidWarehousePlacementPolicy.Route.DIRECT, route(state, "target", 2));
    }

    @Test
    void emptyMaterialsStayWarehouseOwnedAndTheirExpiryWakesPrinting() {
        var state = enabledState();
        state.receiveResult(state.beginRequest(MATERIALS, "target", 0), 2, 1);
        assertEquals(VoidWarehousePlacementPolicy.Route.MATERIAL_UNAVAILABLE, route(state, "target", 1));
        assertEquals(VoidWarehousePlacementPolicy.Route.MATERIAL_UNAVAILABLE, route(state, "target", 100));
        assertFalse(state.consumeMaterialAvailabilityChange());
        state.tick(101);
        assertTrue(state.consumeMaterialAvailabilityChange(), "A parked material workflow must wake when stock can be retried");
        assertEquals(VoidWarehousePlacementPolicy.Route.DIRECT, route(state, "target", 101));
    }

    @Test
    void trafficAndPendingLimitsWaitWithoutInventoryFallback() {
        var state = enabledState();
        for (int i = 0; i < 32; i++) assertTrue(state.beginRequest(MATERIALS, "target-" + i, 0) > 0);
        assertEquals(VoidWarehousePlacementPolicy.Route.WAIT, route(state, "new-target", 0));
        assertEquals(VoidWarehousePlacementPolicy.Route.WAIT, route(state, "target-0", 1));
        assertEquals(VoidWarehousePlacementPolicy.Route.DIRECT, route(state, "new-target", 1));
        state.receiveResult(1, 4, 1);
        assertEquals(VoidWarehousePlacementPolicy.Route.WAIT, route(state, "new-target", 2));
        assertEquals(VoidWarehousePlacementPolicy.Route.DIRECT, route(state, "new-target", 6));
    }

    @Test
    void refreshTimeoutKeepsConfirmedDirectSupportUntilAnExplicitRuleOffReply() {
        var state = enabledState();
        int expiredHello = state.beginHello(100);
        state.tick(200);
        assertEquals(VoidWarehousePlacementPolicy.Route.DIRECT, route(state, "target", 200));
        int currentHello = state.beginHello(200);
        state.receiveHello(expiredHello, false, 201);
        assertEquals(VoidWarehousePlacementPolicy.Route.DIRECT, route(state, "target", 201));
        state.receiveHello(currentHello, false, 202);
        assertEquals(VoidWarehousePlacementPolicy.Route.INVENTORY, route(state, "target", 202));
        state.receiveHello(expiredHello, true, 203);
        assertEquals(VoidWarehousePlacementPolicy.Route.INVENTORY, route(state, "target", 203));
    }

    @Test
    void unsupportedWarehouseCapabilityKeepsTheOriginalInventoryPath() {
        assertEquals(VoidWarehousePlacementPolicy.Route.INVENTORY,
                VoidWarehousePlacementPolicy.route(false, true, true, true));
        assertEquals(VoidWarehousePlacementPolicy.Route.INVENTORY,
                VoidWarehousePlacementPolicy.route(false, false, false, false));
        var disabled = new VoidWarehousePlacementState<String>();
        disabled.receiveHello(disabled.beginHello(0), false, 0);
        assertEquals(VoidWarehousePlacementPolicy.Route.INVENTORY, route(disabled, "target", 0));
    }

    private static void assertFailureWaits(int status) {
        var state = enabledState();
        state.receiveResult(state.beginRequest(MATERIALS, "target", 0), status, 1);
        assertEquals(VoidWarehousePlacementPolicy.Route.WAIT, route(state, "target", 1));
        assertEquals(VoidWarehousePlacementPolicy.Route.WAIT, route(state, "target", 20));
        assertEquals(VoidWarehousePlacementPolicy.Route.DIRECT, route(state, "other-target", 1));
        assertEquals(VoidWarehousePlacementPolicy.Route.DIRECT, route(state, "target", 21));
    }

    private static VoidWarehousePlacementPolicy.Route route(VoidWarehousePlacementState<String> state,
                                                            String target, long tick) {
        return VoidWarehousePlacementPolicy.route(state.isEnabled(),
                !state.eligibleCandidates(MATERIALS, tick).isEmpty(),
                state.isTargetAvailable(target, tick), state.canRequest(MATERIALS, target, tick));
    }

    private static VoidWarehousePlacementState<String> enabledState() {
        var state = new VoidWarehousePlacementState<String>();
        state.receiveHello(state.beginHello(0), true, 0);
        state.consumeMaterialAvailabilityChange();
        return state;
    }
}
