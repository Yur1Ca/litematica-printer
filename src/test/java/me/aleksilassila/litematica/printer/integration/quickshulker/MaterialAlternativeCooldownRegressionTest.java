//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import me.aleksilassila.litematica.printer.integration.inventory.InventoryProvider;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequestCoordinator;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation;
import me.aleksilassila.litematica.printer.integration.inventory.PlayerInventoryProvider;
import me.aleksilassila.litematica.printer.integration.voidwarehouse.VoidWarehouseRestockPort;
import me.aleksilassila.litematica.printer.printer.action.ActionPort;
import neko.voidwarehouse.restock.PrinterTakeResultPayload;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static me.aleksilassila.litematica.printer.integration.quickshulker.CancelledSwapLifecycleTest.set;
import static me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerPacketContractTest.allocate;
import static org.junit.jupiter.api.Assertions.*;

/** Real provider chain and broker; only server stock/replies use the existing network port. */
class MaterialAlternativeCooldownRegressionTest {
    private boolean oldEnabled;

    @BeforeAll static void bootstrap() throws Exception { CancelledSwapLifecycleTest.bootstrap(); }
    @BeforeEach void enable() {
        oldEnabled = Configs.Special.VOID_WAREHOUSE_RESTOCK.getBooleanValue();
        Configs.Special.VOID_WAREHOUSE_RESTOCK.setBooleanValue(true);
    }
    @AfterEach void restore() { Configs.Special.VOID_WAREHOUSE_RESTOCK.setBooleanValue(oldEnabled); }

    private static final class StockNetwork implements VoidWarehouseRestockPort {
        final Set<String> stock = new HashSet<>();
        final List<List<String>> sent = new ArrayList<>();
        int response = PrinterTakeResultPayload.NONE;

        @Override public boolean isSupported() { return true; }
        @Override public int requestTake(List<String> candidates) {
            sent.add(List.copyOf(candidates));
            response = candidates.stream().anyMatch(stock::contains)
                    ? PrinterTakeResultPayload.OK : PrinterTakeResultPayload.EMPTY;
            return sent.size();
        }
        @Override public int statusOf(int nonce) { return response; }
        @Override public void forget() { response = PrinterTakeResultPayload.NONE; }
    }

    private static final class Fixture extends VoidWarehouseRestockRegressionTest.Fixture {
        final StockNetwork stock = new StockNetwork();
        final InventoryProvider provider = (InventoryProvider) warehouseType
                .getConstructor(ActionPort.class, VoidWarehouseRestockPort.class).newInstance(broker, stock);
        final MaterialRequestCoordinator chain = new MaterialRequestCoordinator(List.of(
                new PlayerInventoryProvider(client), provider));

        Fixture() throws Exception { clock.setGameTime(1_000L); }

        MaterialReservation request(List<Item> items) {
            return chain.request(items, Items.STONE, MaterialRequest.Source.PRINT);
        }

        void fail(List<Item> items) {
            assertEquals(MaterialReservation.State.PENDING, request(items).state());
            assertEquals(MaterialReservation.State.UNAVAILABLE, request(items).state());
            assertFalse(chain.isBusy());
            assertFalse(broker.isResourceHeld(ResourceLease.INVENTORY));
        }

        void tickChain(int ticks) {
            for (int tick = 0; tick < ticks; tick++) {
                clock.setGameTime(clock.getGameTime() + 1L);
                chain.tick();
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"expanded", "replaced", "reordered"})
    void aChangedAcceptedListCanAcquireItsAlternativeDuringTheOldFailureCooldown(String change) throws Exception {
        try (var f = new Fixture()) {
            var before = switch (change) {
                case "expanded" -> List.of(Items.STONE);
                case "replaced" -> List.of(Items.STONE, Items.COBBLESTONE);
                default -> List.of(Items.STONE, Items.DIRT, Items.COBBLESTONE);
            };
            var after = change.equals("reordered")
                    ? List.of(Items.STONE, Items.COBBLESTONE, Items.DIRT)
                    : List.of(Items.STONE, Items.DIRT);
            f.fail(before);
            f.stock.stock.add("minecraft:dirt");

            assertEquals(MaterialReservation.State.PENDING, f.request(after).state(),
                    "A failed preferred item must not suppress a different accepted list");
            assertEquals(change.equals("reordered")
                    ? List.of("minecraft:stone", "minecraft:cobblestone", "minecraft:dirt")
                    : List.of("minecraft:stone", "minecraft:dirt"), f.stock.sent.get(1));
            f.assertHeld();

            f.tickChain(90);
            assertTrue(f.chain.isBusy(), "An OK receipt must still wait for the real inventory update");
            f.assertHeld();
            f.player.inventory.setItem(10, new ItemStack(Items.DIRT, 64));
            var delivered = f.request(after);
            assertEquals(MaterialReservation.State.AVAILABLE, delivered.state());
            assertSame(Items.DIRT, delivered.selectedItem());
            assertFalse(f.chain.isBusy());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertEquals(2, f.stock.sent.size());
        }
    }

    @Test void theSameRequirementKeepsItsOriginalCooldownAcrossNewTokens() throws Exception {
        try (var f = new Fixture()) {
            var items = List.of(Items.STONE, Items.DIRT);
            f.fail(items);
            f.stock.stock.add("minecraft:dirt");

            assertEquals(MaterialReservation.State.UNAVAILABLE, f.request(items).state());
            f.clock.setGameTime(1_099L);
            assertEquals(MaterialReservation.State.UNAVAILABLE, f.request(items).state());
            assertEquals(1, f.stock.sent.size(), "Repeated failures must neither resend nor refresh the cooldown");
            f.clock.setGameTime(1_100L);
            assertEquals(MaterialReservation.State.PENDING, f.request(items).state());
            assertEquals(2, f.stock.sent.size());
        }
    }

    @Test void relaxingTheEmptyShulkerConstraintDoesNotInheritTheFailedStrictRequest() throws Exception {
        try (var f = new Fixture()) {
            assertEquals(MaterialReservation.State.PENDING, f.chain.requestEmptyShulker(Items.SHULKER_BOX).state());
            assertEquals(MaterialReservation.State.UNAVAILABLE, f.chain.requestEmptyShulker(Items.SHULKER_BOX).state());
            f.stock.stock.add("minecraft:shulker_box");

            assertEquals(MaterialReservation.State.PENDING,
                    f.chain.request(Items.SHULKER_BOX, MaterialRequest.Source.PRINT).state());
            assertEquals(2, f.stock.sent.size());
            f.player.inventory.setItem(10, new ItemStack(Items.SHULKER_BOX));
            assertEquals(MaterialReservation.State.AVAILABLE,
                    f.chain.request(Items.SHULKER_BOX, MaterialRequest.Source.PRINT).state());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
        }
    }

    @Test void changingPreferredItemPreservesItsPriorityInTheSentCandidates() throws Exception {
        try (var f = new Fixture()) {
            f.fail(List.of(Items.STONE, Items.DIRT));
            f.stock.stock.add("minecraft:dirt");

            assertEquals(MaterialReservation.State.PENDING, f.chain.request(
                    List.of(Items.STONE, Items.DIRT), Items.DIRT, MaterialRequest.Source.PRINT).state());
            assertEquals(List.of("minecraft:dirt", "minecraft:stone"), f.stock.sent.get(1));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"PICK_BLOCK", "FILL", "BEDROCK"})
    void changingOnlyTheCallerKeepsTheFailureCooldown(String source) throws Exception {
        try (var f = new Fixture()) {
            var items = List.of(Items.STONE, Items.DIRT);
            f.fail(items);
            f.stock.stock.add("minecraft:dirt");
            assertEquals(MaterialReservation.State.UNAVAILABLE,
                    f.chain.request(items, Items.STONE, MaterialRequest.Source.valueOf(source)).state());
            assertEquals(1, f.stock.sent.size());
            assertFalse(f.chain.isBusy());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertEquals(0, f.player.inventory.getSelectedSlot());
        }
    }

    @Test void aNewAcceptedListWaitsForTheInventoryLeaseBeforeSending() throws Exception {
        try (var f = new Fixture()) {
            f.fail(List.of(Items.STONE));
            f.stock.stock.add("minecraft:dirt");
            assertTrue(f.broker.tryAcquire("container_sync", EnumSet.of(ResourceLease.INVENTORY,
                    ResourceLease.CONTAINER, ResourceLease.MAIN_HAND), 0L));
            var expanded = List.of(Items.STONE, Items.DIRT);

            assertEquals(MaterialReservation.State.PENDING, f.request(expanded).state());
            f.tickChain(300);
            assertTrue(f.chain.isBusy(), "Waiting for the lease must not consume the response timeout");
            assertEquals(1, f.stock.sent.size());
            assertTrue(f.broker.isResourceHeldByOther(ResourceLease.INVENTORY, "void_warehouse"));

            f.broker.releaseOwner("container_sync");
            f.tickChain(1);
            assertEquals(2, f.stock.sent.size());
            f.tickChain(90);
            assertTrue(f.chain.isBusy());
            f.assertHeld();
            f.player.inventory.setItem(10, new ItemStack(Items.DIRT, 64));
            assertEquals(MaterialReservation.State.AVAILABLE, f.request(expanded).state());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"restock_off", "connection", "level"})
    void cancellationOfTheAlternativeRequestReleasesItsLeaseAndAllowsAFreshRequest(String reason) throws Exception {
        try (var f = new Fixture()) {
            f.fail(List.of(Items.STONE));
            f.stock.stock.add("minecraft:dirt");
            var expanded = List.of(Items.STONE, Items.DIRT);
            assertEquals(MaterialReservation.State.PENDING, f.request(expanded).state());
            f.assertHeld();

            if (reason.equals("restock_off")) {
                Configs.Special.VOID_WAREHOUSE_RESTOCK.setBooleanValue(false);
                f.tickChain(1);
            } else {
                if (reason.equals("connection")) {
                    set(f.player, LocalPlayer.class, "connection", allocate(CancelledSwapLifecycleTest.Connection.class));
                } else {
                    f.client.level = allocate(ClientLevel.class);
                    set(f.client.level, Level.class, "levelData", f.clock);
                    set(f.client.level, ClientLevel.class, "clientLevelData", f.clock);
                    f.clock.setGameTime(10L);
                }
                f.chain.onEpochChanged(new RuntimeEvent.EpochChanged(
                        RuntimeEpoch.INITIAL, RuntimeEpoch.INITIAL.next(), reason));
            }

            assertFalse(f.chain.isBusy());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            f.player.inventory.setItem(10, new ItemStack(Items.DIRT, 64));
            f.tickChain(1);
            assertEquals(2, f.stock.sent.size(), "Late delivery must not replay the cancelled request");
            assertEquals(0, f.player.inventory.getSelectedSlot());

            f.player.inventory.setItem(10, ItemStack.EMPTY);
            Configs.Special.VOID_WAREHOUSE_RESTOCK.setBooleanValue(true);
            assertEquals(MaterialReservation.State.PENDING, f.request(expanded).state());
            assertEquals(3, f.stock.sent.size());
        }
    }
}
//#endif
