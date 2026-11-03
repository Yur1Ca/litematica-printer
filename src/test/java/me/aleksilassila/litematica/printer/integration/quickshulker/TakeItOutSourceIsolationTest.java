//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.util.List;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.core.runtime.FeatureLifecycle;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeScope;
import me.aleksilassila.litematica.printer.integration.inventory.InventoryProvider;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequestCoordinator;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation;
import me.aleksilassila.litematica.printer.integration.inventory.TakeItOutAdapter;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.action.ActionBroker;
import me.aleksilassila.litematica.printer.printer.action.ActionPort;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static me.aleksilassila.litematica.printer.integration.quickshulker.CancelledSwapLifecycleTest.get;
import static me.aleksilassila.litematica.printer.integration.quickshulker.CancelledSwapLifecycleTest.set;
import static me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerPacketContractTest.allocate;
import static org.junit.jupiter.api.Assertions.*;

/** The foreign pick source still shares inventory ownership, but may only use Take It Out. */
class TakeItOutSourceIsolationTest {
    @BeforeAll static void bootstrap() throws Exception { CancelledSwapLifecycleTest.bootstrap(); }

    private static class Api extends TakeItOutRequestOwnershipTest.ExternalApi {
        boolean accepts = true;
        int attempts;

        @Override public boolean tryRequestItem(Item item, boolean emptyShulkerOnly) {
            attempts++;
            return accepts && super.tryRequestItem(item, emptyShulkerOnly);
        }
    }

    /** Deliberately inherits the ordinary provider support policy. */
    private static class OtherProvider implements InventoryProvider {
        private final String id;
        int calls;
        boolean available;

        OtherProvider(String id) { this.id = id; }
        @Override public String id() { return id; }
        @Override public MaterialReservation request(MaterialRequest request) {
            calls++;
            return available ? MaterialReservation.available(request, request.preferredItem())
                    : new MaterialReservation(request.token(), MaterialReservation.State.UNAVAILABLE);
        }
    }

    private static class Fixture extends PickBlockInventoryExclusionTest.Fixture {
        final FeatureLifecycle lifecycle = new FeatureLifecycle();
        final ActionManager actions = new ActionManager();
        final ActionBroker gate = new ActionBroker(runtime::epoch, () -> lifecycle, actions);
        final Api port = new Api();
        final TakeItOutAdapter tio = new TakeItOutAdapter(gate, port);
        final OtherProvider inventory = new OtherProvider("player_inventory");
        final OtherProvider warehouse = new OtherProvider("void_warehouse");
        final OtherProvider quick = new OtherProvider("quick_shulker");
        final OtherProvider chest = new OtherProvider("chest_tracker");
        final List<OtherProvider> others = List.of(inventory, warehouse, quick, chest);
        final MaterialRequestCoordinator materials = new MaterialRequestCoordinator(
                List.of(inventory, warehouse, tio, quick, chest));
        final boolean oldPrint = Configs.Core.PRINT.getBooleanValue();
        final Object previousInventoryClient;

        Fixture() throws Exception {
            var clock = new ClientLevel.ClientLevelData(Difficulty.NORMAL, false, false);
            set(client.level, Level.class, "levelData", clock);
            set(client.level, ClientLevel.class, "clientLevelData", clock);
            set(runtime, PrinterRuntime.class, "actionBroker", gate);
            set(runtime, PrinterRuntime.class, "materialRequests", materials);
            previousInventoryClient = get(null, InventoryUtils.class, "client");
            setInventoryClient(client);
            lifecycle.observe("print", true);
            holdPrintMaterial();
        }

        void holdPrintMaterial() {
            player.inventory.setSelectedSlot(0);
            player.inventory.setItem(0, new ItemStack(Items.DIRT, 16));
            handEquipment.set(EquipmentSlot.MAINHAND, player.inventory.getItem(0));
            assertTrue(player.getMainHandItem().is(Items.DIRT));
        }

        MaterialReservation foreignPick() {
            return materials.request(Items.STONE, MaterialRequest.Source.TAKE_IT_OUT_PICK);
        }

        boolean queuePrint() {
            return gate.queueClick(BlockPos.ZERO, Direction.UP, Vec3.ZERO, false,
                    1, new Item[]{Items.DIRT}, ActionPort.ActionSource.PRINT);
        }

        void advance(int ticks) { for (int i = 0; i < ticks; i++) materials.tick(); }

        void assertNoOtherProvider() {
            for (var provider : others) assertEquals(0, provider.calls, provider.id());
        }

        void assertHeld(boolean expected) {
            for (var resource : List.of(ResourceLease.MAIN_HAND, ResourceLease.INVENTORY, ResourceLease.CONTAINER)) {
                assertEquals(expected, gate.isResourceHeld(resource), resource.name());
                if (expected) assertFalse(gate.isResourceHeldByOther(resource, "take_it_out"));
            }
        }

        void arrive() {
            player.inventory.setItem(2, new ItemStack(Items.STONE, 16));
            port.pending = ItemStack.EMPTY;
            materials.tick();
        }

        @Override void resetScope(String reason) throws Exception {
            var scope = new RuntimeScope();
            scope.register(gate);
            scope.register(materials);
            var previous = runtime.epoch();
            var next = previous.next();
            set(runtime, PrinterRuntime.class, "epoch", next);
            scope.changeEpoch(new RuntimeEvent.EpochChanged(previous, next, reason));
        }

        @SuppressWarnings("removal")
        private static void setInventoryClient(Object client) throws Exception {
            var access = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            access.setAccessible(true);
            var unsafe = (sun.misc.Unsafe) access.get(null);
            var field = InventoryUtils.class.getDeclaredField("client");
            unsafe.putObject(unsafe.staticFieldBase(field), unsafe.staticFieldOffset(field), client);
        }

        @Override public void close() throws Exception {
            try {
                setInventoryClient(previousInventoryClient);
                Configs.Core.PRINT.setBooleanValue(oldPrint);
            } finally { super.close(); }
        }
    }

    @Test void delayedForeignPickBlocksPrintingEvenWithThePrintMaterialAlreadyInHand() throws Exception {
        try (var f = new Fixture()) {
            // If the leading ordinary provider were consulted, it would incorrectly finish the request.
            f.inventory.available = true;
            assertEquals(MaterialReservation.State.PENDING, f.foreignPick().state());
            long token = f.materials.activeToken();
            int packetsBefore = f.connection.packets.size();
            for (int tick = 0; tick < 60; tick++) {
                assertTrue(f.player.getMainHandItem().is(Items.DIRT));
                assertFalse(f.queuePrint(), "The pending server extraction may replace the main hand");
                assertEquals(ActionPort.SendResult.NO_QUEUED_ACTION, f.gate.sendQueue(f.player));
                f.materials.tick();
                assertEquals(token, f.materials.activeToken());
                f.assertHeld(true);
            }
            assertEquals(packetsBefore, f.connection.packets.size());
            assertEquals(List.of(Items.STONE), f.port.sent);
            assertEquals(1, f.port.attempts);
            f.assertNoOtherProvider();

            f.arrive();

            assertFalse(f.materials.isBusy());
            f.assertHeld(false);
            assertEquals(2, f.player.inventory.getSelectedSlot(), "A completed TIO pick keeps manual pick selection");
            f.holdPrintMaterial();
            assertTrue(f.queuePrint(), "Printing can be admitted after confirmed material arrival");
            f.gate.cancelQueue();
            f.assertHeld(false);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"rejected", "failed", "timeout"})
    void unavailableForeignPickNeverFallsBackToAnotherProvider(String failure) throws Exception {
        try (var f = new Fixture()) {
            f.port.accepts = !failure.equals("rejected");
            // Every forbidden provider would report success if called.
            for (var provider : f.others) provider.available = true;
            var result = f.foreignPick();
            if (failure.equals("rejected")) {
                assertEquals(MaterialReservation.State.UNAVAILABLE, result.state());
                assertTrue(f.port.sent.isEmpty());
            } else {
                assertEquals(MaterialReservation.State.PENDING, result.state());
                f.assertHeld(true);
                if (failure.equals("failed")) {
                    f.port.pending = ItemStack.EMPTY;
                } else {
                    f.advance(80);
                    assertTrue(f.materials.isBusy());
                    f.assertHeld(true);
                }
                f.materials.tick();
                assertEquals(List.of(Items.STONE), f.port.sent);
            }
            f.advance(100);
            assertFalse(f.materials.isBusy());
            assertTrue(f.port.pending.isEmpty());
            assertEquals(1, f.port.attempts);
            f.assertHeld(false);
            f.assertNoOtherProvider();
            assertTrue(f.queuePrint(), "Failure releases the hand without starting another extraction");
            f.gate.cancelQueue();
        }
    }

    @ParameterizedTest @EnumSource(value = MaterialRequest.Source.class, names = {"PRINT", "PICK_BLOCK"})
    void ordinaryPrintAndExplicitPickKeepTheirExistingProviderFallback(MaterialRequest.Source source) throws Exception {
        try (var f = new Fixture()) {
            f.port.accepts = false;
            f.quick.available = true;

            var result = f.materials.request(Items.STONE, source);

            assertEquals(MaterialReservation.State.AVAILABLE, result.state());
            assertSame(Items.STONE, result.selectedItem());
            assertEquals(1, f.inventory.calls);
            assertEquals(1, f.warehouse.calls);
            assertEquals(1, f.port.attempts);
            assertEquals(1, f.quick.calls);
            assertEquals(0, f.chest.calls);
            assertFalse(f.materials.isBusy());
            f.assertHeld(false);
        }
    }

    @ParameterizedTest @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void printerSwitchesAndProductionCancellationDoNotCancelTheForeignManualPick(boolean work, boolean print) throws Exception {
        try (var f = new Fixture()) {
            Configs.Core.WORK_SWITCH.setBooleanValue(work);
            Configs.Core.PRINT.setBooleanValue(print);
            assertEquals(MaterialReservation.State.PENDING, f.foreignPick().state());
            long token = f.materials.activeToken();
            Configs.Core.WORK_SWITCH.setBooleanValue(false);
            Configs.Core.PRINT.setBooleanValue(false);
            Configs.Placement.QUICK_SHULKER.setBooleanValue(false);
            for (String owner : List.of("print", "fill", "fluid", "mine", "cover", "bedrock")) {
                f.lifecycle.observe(owner, false);
                f.gate.cancelOwnerQueue(owner);
                f.materials.cancelOwner(owner);
            }
            for (int tick = 0; tick < 60; tick++) {
                f.materials.tick();
                assertEquals(token, f.materials.activeToken());
                f.assertHeld(true);
            }
            assertEquals(1, f.port.attempts);
            assertEquals(List.of(Items.STONE), f.port.sent);
            f.assertNoOtherProvider();

            f.arrive();

            assertFalse(f.materials.isBusy());
            assertEquals(2, f.player.inventory.getSelectedSlot());
            f.assertHeld(false);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"connection", "level"})
    void aNewClientScopeDiscardsTheForeignTokenAndAllItsLeases(String changed) throws Exception {
        try (var f = new Fixture()) {
            assertEquals(MaterialReservation.State.PENDING, f.foreignPick().state());
            long previousToken = f.materials.activeToken();
            var oldPending = f.port.pending;
            if (changed.equals("connection")) {
                var replacement = allocate(CancelledSwapLifecycleTest.Connection.class);
                replacement.packets = new java.util.ArrayList<>();
                set(f.player, LocalPlayer.class, "connection", replacement);
            } else {
                f.client.level = allocate(ClientLevel.class);
            }

            f.resetScope("client_scope_changed");
            f.advance(100);

            assertFalse(f.materials.isBusy());
            f.assertHeld(false);
            assertSame(oldPending, f.port.pending, "The old adapter cannot clear a marker in a different scope");
            assertEquals(List.of(Items.STONE), f.port.sent);
            f.assertNoOtherProvider();
            // The optional mod clears its own old marker when its client session changes.
            f.port.pending = ItemStack.EMPTY;
            assertEquals(MaterialReservation.State.PENDING,
                    f.materials.request(Items.COBBLESTONE, MaterialRequest.Source.TAKE_IT_OUT_PICK).state());
            assertTrue(f.materials.activeToken() > previousToken);
            assertEquals(List.of(Items.STONE, Items.COBBLESTONE), f.port.sent);
            f.assertHeld(true);
            f.assertNoOtherProvider();
        }
    }

    @Test void anAlreadyAwaitingExternalTioRequestAlsoHoldsTheSharedHandUntilItFinishes() throws Exception {
        try (var f = new Fixture()) {
            var external = new ItemStack(Items.COBBLESTONE);
            f.port.pending = external;
            assertEquals(MaterialReservation.State.PENDING, f.foreignPick().state());
            long token = f.materials.activeToken();
            for (int tick = 0; tick < 300; tick++) {
                assertFalse(f.queuePrint());
                f.materials.tick();
                assertEquals(token, f.materials.activeToken());
                f.assertHeld(true);
            }
            assertSame(external, f.port.pending);
            assertEquals(0, f.port.attempts);
            f.assertNoOtherProvider();

            f.port.pending = ItemStack.EMPTY;
            f.materials.tick();

            assertEquals(List.of(Items.STONE), f.port.sent);
            assertEquals(token, f.materials.activeToken());
            f.assertHeld(true);
            f.arrive();
            assertFalse(f.materials.isBusy());
            f.assertHeld(false);
            f.assertNoOtherProvider();
        }
    }
}
//#endif
