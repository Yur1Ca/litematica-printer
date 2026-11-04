//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.util.List;
import java.util.EnumSet;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation;
import me.aleksilassila.litematica.printer.integration.inventory.PlayerInventoryProvider;
import me.aleksilassila.litematica.printer.integration.inventory.ChestTrackerAdapter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static me.aleksilassila.litematica.printer.integration.quickshulker.CancelledSwapLifecycleTest.set;
import static org.junit.jupiter.api.Assertions.*;

class BedrockMaterialQuantityTest {
    @BeforeAll static void bootstrap() throws Exception { CancelledSwapLifecycleTest.bootstrap(); }

    private static MaterialRequest twoPistons() {
        return new MaterialRequest(1L, List.of(Items.PISTON), Items.PISTON, 2, MaterialRequest.Source.BEDROCK);
    }

    @Test void takeItOutMustWaitForBothPistonsEvenIfItsLegacyMarkerClearsEarly() throws Exception {
        try (var f = new TakeItOutRequestOwnershipTest.Fixture()) {
            f.player.inventory.setItem(10, new ItemStack(Items.PISTON));
            var request = twoPistons();
            assertEquals(MaterialReservation.State.PENDING, f.takeItOut.request(request).state());
            assertEquals(List.of(Items.PISTON), f.api.sent);
            f.api.pending = ItemStack.EMPTY;
            for (int tick = 0; tick < 60; tick++) {
                assertEquals(MaterialReservation.State.PENDING, f.takeItOut.status(request).state());
                assertTrue(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            }
            f.player.inventory.setItem(11, new ItemStack(Items.PISTON));
            assertEquals(MaterialReservation.State.AVAILABLE, f.takeItOut.status(request).state());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertEquals(1, f.api.sent.size());
        }
    }

    @Test void warehouseMustNotCompleteFromTheOnePistonAlreadyCarried() throws Exception {
        boolean enabled = Configs.Special.VOID_WAREHOUSE_RESTOCK.getBooleanValue();
        Configs.Special.VOID_WAREHOUSE_RESTOCK.setBooleanValue(true);
        try (var f = new VoidWarehouseRestockRegressionTest.Fixture()) {
            f.player.inventory.setItem(10, new ItemStack(Items.PISTON));
            var request = twoPistons();
            assertEquals(MaterialReservation.State.PENDING, f.warehouse.request(request).state());
            assertEquals(1, f.network.sends);
            for (int tick = 0; tick < 60; tick++) {
                assertEquals(MaterialReservation.State.PENDING, f.warehouse.status(request).state());
                f.assertHeld();
            }
            f.player.inventory.setItem(40, new ItemStack(Items.PISTON));
            assertEquals(MaterialReservation.State.AVAILABLE, f.warehouse.status(request).state());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
        } finally {
            Configs.Special.VOID_WAREHOUSE_RESTOCK.setBooleanValue(enabled);
        }
    }

    @Test void bedrockCountsTheOffhandWithoutChangingOrdinaryPickBlockAvailability() throws Exception {
        try (var f = new TakeItOutRequestOwnershipTest.Fixture()) {
            f.player.inventory.setItem(10, new ItemStack(Items.PISTON));
            f.player.inventory.setItem(40, new ItemStack(Items.PISTON));
            var provider = new PlayerInventoryProvider(f.client);
            assertEquals(MaterialReservation.State.AVAILABLE, provider.request(twoPistons()).state());
            f.player.inventory.setItem(10, ItemStack.EMPTY);
            var pick = new MaterialRequest(2L, List.of(Items.PISTON), Items.PISTON, 1, MaterialRequest.Source.PICK_BLOCK);
            assertEquals(MaterialReservation.State.UNAVAILABLE, provider.request(pick).state());
        }
    }

    @Test void quickShulkerCompletionRequiresTheRequestedQuantity() throws Exception {
        try (var f = new TakeItOutRequestOwnershipTest.Fixture()) {
            var adapter = new QuickShulkerAdapter(f.broker);
            f.player.inventory.setItem(10, new ItemStack(Items.PISTON));
            set(adapter, QuickShulkerAdapter.class, "attemptedToken", 1L);
            assertEquals(MaterialReservation.State.UNAVAILABLE, adapter.status(twoPistons()).state(),
                    "A completed extraction of only one piston must not satisfy the two-piston request");
            f.player.inventory.setItem(40, new ItemStack(Items.PISTON));
            assertEquals(MaterialReservation.State.AVAILABLE, adapter.status(twoPistons()).state());
        }
    }

    @Test void chestTrackerPollingDoesNotFinishWithOnlyTheOldPartialStack() throws Exception {
        try (var f = new VoidWarehouseRestockRegressionTest.Fixture()) {
            var chest = new ChestTrackerAdapter(f.broker);
            set(chest, ChestTrackerAdapter.class, "activeRequest", twoPistons());
            set(chest, ChestTrackerAdapter.class, "requestedItem", Items.PISTON);
            set(chest, ChestTrackerAdapter.class, "requestedItems", List.of(Items.PISTON));
            set(chest, ChestTrackerAdapter.class, "requestDeadline", 200L);
            var type = ChestTrackerAdapter.class.getDeclaredField("phase").getType();
            @SuppressWarnings({"rawtypes", "unchecked"})
            var phase = Enum.valueOf((Class) type, "WAITING_INVENTORY");
            set(chest, ChestTrackerAdapter.class, "phase", phase);
            assertTrue(f.broker.tryAcquire("chest_tracker", EnumSet.of(ResourceLease.INVENTORY,
                    ResourceLease.CONTAINER, ResourceLease.MAIN_HAND), 0L));
            f.player.inventory.setItem(10, new ItemStack(Items.PISTON));
            for (int tick = 0; tick < 60; tick++) {
                f.clock.setGameTime(tick);
                chest.tick();
                assertEquals(MaterialReservation.State.PENDING, chest.status(twoPistons()).state());
                assertTrue(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            }
            f.player.inventory.setItem(40, new ItemStack(Items.PISTON));
            chest.tick();
            assertEquals(MaterialReservation.State.AVAILABLE, chest.status(twoPistons()).state());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"bedrock", "work_switch_off", "client_scope_changed"})
    void cancellingBedrockLeavesNoLateTakeItOutSelectionOrRepeatedRequest(String reason) throws Exception {
        try (var f = new TakeItOutRequestOwnershipTest.Fixture()) {
            f.player.inventory.setItem(10, new ItemStack(Items.PISTON));
            assertEquals(MaterialReservation.State.PENDING,
                    f.coordinator.request(Items.PISTON, 2, MaterialRequest.Source.BEDROCK).state());
            if (reason.equals("bedrock")) f.coordinator.cancelOwner(reason);
            else f.resetScope(reason);
            f.player.inventory.setItem(11, new ItemStack(Items.PISTON, 64));
            f.tick(100);
            assertFalse(f.coordinator.isBusy());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertTrue(f.api.pending.isEmpty());
            assertEquals(List.of(Items.PISTON), f.api.sent);
            assertEquals(0, f.next.calls);
        }
    }
}
//#endif
