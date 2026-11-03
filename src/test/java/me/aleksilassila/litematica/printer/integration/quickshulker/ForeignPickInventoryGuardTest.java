//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequestCoordinator;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static me.aleksilassila.litematica.printer.integration.quickshulker.CancelledSwapLifecycleTest.get;
import static me.aleksilassila.litematica.printer.integration.quickshulker.CancelledSwapLifecycleTest.set;
import static me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerPacketContractTest.allocate;
import static org.junit.jupiter.api.Assertions.*;

/** The real common callback must arbitrate foreign picks before inspecting any schematic target. */
class ForeignPickInventoryGuardTest {
    private static Method commonPick;

    @BeforeAll static void bootstrap() throws Exception {
        CancelledSwapLifecycleTest.bootstrap();
        var mixin = Class.forName("me.aleksilassila.litematica.printer.mixin.printer.litematica.MixinInventoryUtils");
        commonPick = mixin.getDeclaredMethod("schematicWorldPickBlock", boolean.class,
                Minecraft.class, CallbackInfoReturnable.class);
        commonPick.setAccessible(true);
    }

    private static final class Fixture extends PickBlockInventoryExclusionTest.Fixture {
        private final boolean oldPrint = Configs.Core.PRINT.getBooleanValue();

        Fixture() throws Exception {
            // No target/world is available. An unauthorized call must never enter requiredStack().
            client.level = null;
            client.hitResult = null;
        }

        void assertNoRequest(long nextToken) throws Exception {
            assertFalse(coordinator.isBusy());
            assertEquals(0L, coordinator.activeToken());
            assertEquals(nextToken, get(coordinator, MaterialRequestCoordinator.class, "nextToken"));
            assertTrue(api.sent.isEmpty());
            assertEquals(0, next.calls);
        }

        @Override public void close() throws Exception {
            try { Configs.Core.PRINT.setBooleanValue(oldPrint); } finally { super.close(); }
        }
    }

    private static boolean foreignPick(Fixture fixture, boolean closest) throws Exception {
        var callback = new CallbackInfoReturnable<Boolean>("doSchematicWorldPickBlock", true);
        commonPick.invoke(null, closest, fixture.client, callback);
        if (callback.isCancelled()) assertEquals(Boolean.TRUE, callback.getReturnValue());
        return callback.isCancelled();
    }

    static Stream<Arguments> leaseCases() {
        return Stream.of(ResourceLease.INVENTORY, ResourceLease.CONTAINER, ResourceLease.MAIN_HAND)
                .flatMap(resource -> Stream.of(false, true).flatMap(work ->
                        Stream.of(-1, 0, 2, 9).map(slot -> Arguments.of(resource, work, slot))));
    }

    @ParameterizedTest @MethodSource("leaseCases")
    void foreignPickCannotBypassAnyLeaseOrStartARequest(ResourceLease resource, boolean work, int materialSlot)
            throws Exception {
        try (var f = new Fixture()) {
            Configs.Core.WORK_SWITCH.setBooleanValue(work);
            Configs.Core.PRINT.setBooleanValue(work);
            Configs.Placement.QUICK_SHULKER.setBooleanValue(false);
            f.player.inventory.setSelectedSlot(0);
            if (materialSlot >= 0) f.player.inventory.setItem(materialSlot, new ItemStack(Items.STONE, 16));
            f.handEquipment.set(EquipmentSlot.MAINHAND, f.player.inventory.getItem(0));
            long nextToken = (long) get(f.coordinator, MaterialRequestCoordinator.class, "nextToken");
            int packets = f.connection.packets.size();
            assertTrue(f.broker.tryAcquire("delayed_inventory_cleanup", EnumSet.of(resource), 0L));

            for (int tick = 0; tick < 300; tick++) {
                assertTrue(foreignPick(f, tick % 2 == 0), "A foreign pick must wait for the existing owner");
                f.coordinator.tick();
            }

            f.assertNoRequest(nextToken);
            assertTrue(f.broker.isResourceHeld(resource));
            assertFalse(f.broker.isResourceHeldByOther(resource, "delayed_inventory_cleanup"));
            assertEquals(0, f.player.inventory.getSelectedSlot());
            assertEquals(packets, f.connection.packets.size());

            f.broker.releaseOwner("delayed_inventory_cleanup");

            assertFalse(foreignPick(f, true), "Releasing the lease must restore native foreign picking");
            assertFalse(foreignPick(f, false));
            f.assertNoRequest(nextToken);
            assertEquals(packets, f.connection.packets.size());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"connection", "level"})
    void aClientScopeChangeReleasesTheForeignPickGuardWithoutStartingARequest(String changed) throws Exception {
        try (var f = new Fixture()) {
            Configs.Core.WORK_SWITCH.setBooleanValue(false);
            Configs.Core.PRINT.setBooleanValue(false);
            long nextToken = (long) get(f.coordinator, MaterialRequestCoordinator.class, "nextToken");
            var resources = EnumSet.of(ResourceLease.INVENTORY, ResourceLease.CONTAINER, ResourceLease.MAIN_HAND);
            assertTrue(f.broker.tryAcquire("old_connection", resources, 0L));
            assertTrue(foreignPick(f, false));

            if (changed.equals("connection")) {
                set(f.player, LocalPlayer.class, "connection", allocate(CancelledSwapLifecycleTest.Connection.class));
            } else {
                f.client.level = allocate(ClientLevel.class);
            }
            f.resetScope("client_scope_changed");

            for (var resource : resources) assertFalse(f.broker.isResourceHeld(resource));
            // The replacement level above is only an epoch marker, not a renderable world.
            // During the unload phase no schematic query or fresh request may be attempted.
            f.client.level = null;
            assertFalse(foreignPick(f, false));
            f.assertNoRequest(nextToken);
        }
    }

    @Test void aForeignPickDoesNotReplaceOrPollAnExistingMaterialRequest() throws Exception {
        try (var f = new Fixture()) {
            assertEquals(MaterialReservation.State.PENDING,
                    f.coordinator.request(Items.STONE, MaterialRequest.Source.PRINT).state());
            long activeToken = f.coordinator.activeToken();
            long nextToken = (long) get(f.coordinator, MaterialRequestCoordinator.class, "nextToken");
            Configs.Core.WORK_SWITCH.setBooleanValue(false);
            Configs.Core.PRINT.setBooleanValue(false);
            // Arrival could complete this token if the foreign callback accidentally polls the chain.
            f.player.inventory.setItem(2, new ItemStack(Items.STONE, 16));

            for (int call = 0; call < 20; call++) assertTrue(foreignPick(f, call % 2 == 0));

            assertEquals(activeToken, f.coordinator.activeToken());
            assertEquals(nextToken, get(f.coordinator, MaterialRequestCoordinator.class, "nextToken"));
            assertEquals(List.of(Items.STONE), f.api.sent);
            assertEquals(0, f.next.calls);
            assertTrue(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertTrue(f.broker.isResourceHeld(ResourceLease.CONTAINER));
            assertTrue(f.broker.isResourceHeld(ResourceLease.MAIN_HAND));
        }
    }

    @Test void aForeignRequestCannotAdvanceAnotherSourceDuringALeaseFreeWait() throws Exception {
        try (var f = new Fixture()) {
            var waiting = new me.aleksilassila.litematica.printer.integration.inventory.InventoryProvider() {
                @Override public String id() { return "waiting"; }
                @Override public MaterialReservation request(MaterialRequest request) {
                    return new MaterialReservation(request.token(), MaterialReservation.State.PENDING);
                }
            };
            var requests = new MaterialRequestCoordinator(List.of(waiting, f.next));
            set(f.runtime, me.aleksilassila.litematica.printer.runtime.PrinterRuntime.class, "materialRequests", requests);
            requests.request(Items.DIRT, MaterialRequest.Source.PRINT);
            long token = requests.activeToken();
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));

            for (int call = 0; call < 20; call++) {
                assertTrue(me.aleksilassila.litematica.printer.utils.mods.QuickShulkerBridge
                        .handleTakeItOutPickBlock(f.player, new ItemStack(Items.STONE)));
            }

            assertEquals(token, requests.activeToken());
            assertEquals(0, f.next.calls, "A foreign callback must not advance PRINT into another material provider");
            requests.tick();
            assertEquals(1, f.next.calls, "The original PRINT request still advances through its normal tick");
        }
    }

    @Test void aRejectedForeignRequestCannotRetryThroughTheUnguardedNativeHook() throws Exception {
        try (var f = new Fixture()) {
            var requests = new MaterialRequestCoordinator(List.of(f.next));
            set(f.runtime, me.aleksilassila.litematica.printer.runtime.PrinterRuntime.class, "materialRequests", requests);
            assertTrue(me.aleksilassila.litematica.printer.utils.mods.QuickShulkerBridge
                    .handleTakeItOutPickBlock(f.player, new ItemStack(Items.STONE)));
            assertFalse(requests.isBusy());
            assertEquals(0, f.next.calls);
            assertTrue(f.api.sent.isEmpty());
        }
    }
}
//#endif
