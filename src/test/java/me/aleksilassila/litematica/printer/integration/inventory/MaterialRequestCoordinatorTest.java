package me.aleksilassila.litematica.printer.integration.inventory;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialRequestCoordinatorTest {
    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void repeatedAndCompetingRequestsShareOneActiveToken() {
        FakeProvider provider = new FakeProvider("external", MaterialReservation.State.PENDING);
        MaterialRequestCoordinator coordinator = new MaterialRequestCoordinator(List.of(provider));

        MaterialReservation first = coordinator.request(Items.STONE, MaterialRequest.Source.PRINT);
        MaterialReservation repeated = coordinator.request(Items.STONE, MaterialRequest.Source.PRINT);
        MaterialReservation competing = coordinator.request(Items.GLASS, MaterialRequest.Source.PICK_BLOCK);

        assertEquals(first.token(), repeated.token());
        assertEquals(first.token(), competing.token());
        assertEquals(1, provider.requestCount);
        assertSame(Items.STONE, coordinator.activeItem());
    }

    @Test
    void unavailableProviderFallsThroughInDeclaredOrder() {
        FakeProvider first = new FakeProvider("first", MaterialReservation.State.UNAVAILABLE);
        FakeProvider second = new FakeProvider("second", MaterialReservation.State.PENDING);
        MaterialRequestCoordinator coordinator = new MaterialRequestCoordinator(List.of(first, second));

        MaterialReservation result = coordinator.request(Items.STONE, MaterialRequest.Source.PRINT);

        assertEquals(MaterialReservation.State.PENDING, result.state());
        assertEquals(1, first.requestCount);
        assertEquals(1, second.requestCount);
    }

    @Test
    void pendingProviderConsumesRequestWithoutCallingLowerPriorityProviders() {
        FakeProvider inventory = new FakeProvider("player_inventory", MaterialReservation.State.UNAVAILABLE);
        FakeProvider takeItOut = new FakeProvider("take_it_out", MaterialReservation.State.PENDING);
        FakeProvider quickShulker = new FakeProvider("quick_shulker", MaterialReservation.State.PENDING);
        FakeProvider chestTracker = new FakeProvider("chest_tracker", MaterialReservation.State.PENDING);
        MaterialRequestCoordinator coordinator = new MaterialRequestCoordinator(List.of(
                inventory,
                takeItOut,
                quickShulker,
                chestTracker
        ));

        MaterialReservation result = coordinator.request(Items.STONE, MaterialRequest.Source.PICK_BLOCK);

        assertEquals(MaterialReservation.State.PENDING, result.state());
        assertEquals(1, inventory.requestCount);
        assertEquals(1, takeItOut.requestCount);
        assertEquals(0, quickShulker.requestCount);
        assertEquals(0, chestTracker.requestCount);
    }

    @Test
    void alternativesAreOneAtomicRequestInsteadOfCompetingTokens() {
        FakeProvider provider = new FakeProvider("external", MaterialReservation.State.PENDING);
        MaterialRequestCoordinator coordinator = new MaterialRequestCoordinator(List.of(provider));

        MaterialReservation result = coordinator.request(
                new Item[]{Items.STRIPPED_OAK_LOG, Items.OAK_LOG},
                MaterialRequest.Source.PRINT
        );

        assertEquals(MaterialReservation.State.PENDING, result.state());
        assertEquals(1, provider.requestCount);
        assertEquals(List.of(Items.STRIPPED_OAK_LOG, Items.OAK_LOG), provider.lastRequest.acceptedItems());
        assertSame(Items.STRIPPED_OAK_LOG, provider.lastRequest.preferredItem());
        assertEquals(2, coordinator.activeItems().size());
    }

    @Test
    void timedOutProviderCannotBlockTheChainForever() {
        FakeProvider stalled = new FakeProvider("stalled", MaterialReservation.State.PENDING);
        FakeProvider fallback = new FakeProvider("fallback", MaterialReservation.State.AVAILABLE);
        MaterialRequestCoordinator coordinator = new MaterialRequestCoordinator(List.of(stalled, fallback));
        coordinator.request(Items.STONE, MaterialRequest.Source.PRINT);

        for (int tick = 0; tick < 81; tick++) {
            coordinator.tick();
        }

        assertEquals(1, stalled.requestCount);
        assertEquals(1, stalled.resetCount);
        assertEquals(1, fallback.requestCount);
        assertFalse(coordinator.isBusy());
    }

    @Test
    void epochResetClearsTokenAndProviders() {
        FakeProvider provider = new FakeProvider("external", MaterialReservation.State.PENDING);
        MaterialRequestCoordinator coordinator = new MaterialRequestCoordinator(List.of(provider));
        coordinator.request(Items.STONE, MaterialRequest.Source.PRINT);

        coordinator.reset();

        assertFalse(coordinator.isBusy());
        assertEquals(1, provider.resetCount);
        assertTrue(coordinator.activeToken() == 0L);
    }

    @Test
    void providerCanStayPendingWithoutPausingPrinter() {
        FakeProvider provider = new FakeProvider("non_blocking", MaterialReservation.State.PENDING, 3L, false);
        MaterialRequestCoordinator coordinator = new MaterialRequestCoordinator(List.of(provider));

        coordinator.request(Items.STONE, MaterialRequest.Source.PRINT);

        assertFalse(coordinator.blocksPrinterWhilePending());
        coordinator.tick();
        coordinator.tick();
        assertTrue(coordinator.isBusy());
    }

    @Test
    void cancellingOneFeatureDoesNotCancelAnotherOrManualPickBlock() {
        for (MaterialRequest.Source source : List.of(MaterialRequest.Source.PRINT, MaterialRequest.Source.PICK_BLOCK)) {
            FakeProvider provider = new FakeProvider("external", MaterialReservation.State.PENDING);
            var coordinator = new MaterialRequestCoordinator(List.of(provider));
            coordinator.request(Items.STONE, source);
            long token = coordinator.activeToken();
            coordinator.cancelOwner("fill");
            assertEquals(token, coordinator.activeToken());
            assertEquals(0, provider.resetCount);
            coordinator.cancelOwner(source.name().toLowerCase(java.util.Locale.ROOT));
            assertFalse(coordinator.isBusy());
            assertEquals(1, provider.resetCount);
        }
    }

    @Test
    void cancelledSourceCannotAdvanceToFallbackOnLaterTicks() {
        FakeProvider active = new FakeProvider("active", MaterialReservation.State.PENDING);
        FakeProvider fallback = new FakeProvider("fallback", MaterialReservation.State.AVAILABLE);
        var coordinator = new MaterialRequestCoordinator(List.of(active, fallback));
        coordinator.request(Items.COBBLESTONE, MaterialRequest.Source.MINE);
        coordinator.cancelOwner("mine");
        for (int tick = 0; tick < 300; tick++) coordinator.tick();
        assertEquals(0, fallback.requestCount);
        assertEquals(1, active.resetCount);
        assertFalse(coordinator.isBusy());
    }

    private static final class FakeProvider implements InventoryProvider {
        private final String id;
        private final MaterialReservation.State state;
        private int requestCount;
        private int resetCount;
        private MaterialRequest lastRequest;
        private final long timeoutTicks;
        private final boolean blocksPrinter;

        private FakeProvider(String id, MaterialReservation.State state) {
            this(id, state, 80L, true);
        }

        private FakeProvider(String id, MaterialReservation.State state, long timeoutTicks, boolean blocksPrinter) {
            this.id = id;
            this.state = state;
            this.timeoutTicks = timeoutTicks;
            this.blocksPrinter = blocksPrinter;
        }

        @Override
        public String id() {
            return this.id;
        }

        @Override
        public MaterialReservation request(MaterialRequest request) {
            this.requestCount++;
            this.lastRequest = request;
            return new MaterialReservation(request.token(), this.state);
        }

        @Override
        public MaterialReservation status(MaterialRequest request) {
            return new MaterialReservation(request.token(), this.state);
        }

        @Override
        public long pendingTimeoutTicks() {
            return this.timeoutTicks;
        }

        @Override
        public boolean blocksPrinterWhilePending() {
            return this.blocksPrinter;
        }

        @Override
        public void reset() {
            this.resetCount++;
        }
    }
}
