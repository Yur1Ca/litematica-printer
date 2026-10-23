//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.util.List;
import java.util.EnumSet;
import java.util.stream.Stream;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.integration.containersync.ContainerSyncController;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequestCoordinator;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.utils.mods.QuickShulkerBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static me.aleksilassila.litematica.printer.integration.quickshulker.CancelledSwapLifecycleTest.set;
import static me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerPacketContractTest.allocate;
import static org.junit.jupiter.api.Assertions.*;

class PickBlockInventoryExclusionTest {
    @BeforeAll static void bootstrap() throws Exception { CancelledSwapLifecycleTest.bootstrap(); }

    static class Fixture extends TakeItOutRequestOwnershipTest.Fixture {
        final ContainerSyncController sync = new ContainerSyncController(runtime);
        final net.minecraft.world.entity.EntityEquipment handEquipment = new net.minecraft.world.entity.EntityEquipment();

        Fixture() throws Exception {
            client.gameMode = allocate(MultiPlayerGameMode.class);
            var info = allocate(PlayerInfo.class);
            set(info, PlayerInfo.class, "gameMode", GameType.SURVIVAL);
            set(player, AbstractClientPlayer.class, "playerInfo", info);
            set(player, net.minecraft.world.entity.LivingEntity.class, "equipment", handEquipment);
            set(runtime, PrinterRuntime.class, "materialRequests", coordinator);
            var guiField = Minecraft.class.getDeclaredField("gui");
            set(client, Minecraft.class, "gui", allocate(guiField.getType()));
            Configs.Placement.QUICK_SHULKER.setBooleanValue(false);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        void startSync(String phase) throws Exception {
            var type = ContainerSyncController.class.getDeclaredField("phase").getType();
            set(sync, ContainerSyncController.class, "phase", Enum.valueOf((Class) type, phase));
            set(sync, ContainerSyncController.class, "level", client.level);
            set(sync, ContainerSyncController.class, "connection", client.getConnection());
            set(sync, ContainerSyncController.class, "requestedAt", System.nanoTime() / 1_000_000L);
            var acquire = ContainerSyncController.class.getDeclaredMethod("acquire");
            acquire.setAccessible(true);
            assertEquals(true, acquire.invoke(sync));
        }

        void assertSyncStillOwnsInventory() {
            assertTrue(broker.isResourceHeldByOther(ResourceLease.INVENTORY, "take_it_out"));
            assertFalse(broker.isResourceHeldByOther(ResourceLease.INVENTORY, "container_sync"));
            assertTrue(broker.isResourceHeld(ResourceLease.CONTAINER));
            assertTrue(broker.isResourceHeld(ResourceLease.MAIN_HAND));
        }

        void useUnavailableProviders() throws Exception {
            coordinator.reset();
            set(runtime, PrinterRuntime.class, "materialRequests", new MaterialRequestCoordinator(List.of(next)));
        }
    }

    static Stream<Arguments> existingMaterialCases() {
        return Stream.of("middle_click", "schematic_pick", "easy_place").flatMap(entry ->
                Stream.of(0, 2, 9).filter(slot -> slot != 0 || entry.equals("easy_place")).flatMap(slot ->
                        Stream.of(false, true).flatMap(hiddenMenu ->
                                Stream.of(false, true).map(work -> Arguments.of(entry, slot, hiddenMenu, work)))));
    }

    private static boolean manualEntry(String entry, Fixture fixture) throws Exception {
        if (entry.equals("middle_click")) return QuickShulkerBridge.handlePickBlock(fixture.player, Items.STONE);
        if (entry.equals("schematic_pick")) return QuickShulkerBridge.handlePickBlock(fixture.player, new ItemStack(Items.STONE));
        var type = Class.forName("me.aleksilassila.litematica.printer.mixin.printer.litematica.MixinEasyPlaceUtils");
        var method = type.getDeclaredMethod("litematicaPrinter$restock", CallbackInfoReturnable.class, ItemStack.class);
        method.setAccessible(true);
        var callback = new CallbackInfoReturnable<InteractionResult>("handleEasyPlace", true);
        method.invoke(null, callback, new ItemStack(Items.STONE));
        if (callback.isCancelled()) assertSame(InteractionResult.FAIL, callback.getReturnValue());
        return callback.isCancelled();
    }

    @ParameterizedTest @MethodSource("existingMaterialCases")
    void existingMaterialsCannotBypassAnotherInventoryOwner(String entry, int materialSlot,
                                                           boolean hiddenMenu, boolean work) throws Exception {
        try (var f = new Fixture()) {
            Configs.Core.WORK_SWITCH.setBooleanValue(work);
            f.player.inventory.setSelectedSlot(0);
            f.player.inventory.setItem(0, new ItemStack(Items.DIRT, 16));
            f.player.inventory.setItem(materialSlot, new ItemStack(Items.STONE, 16));
            f.handEquipment.set(net.minecraft.world.entity.EquipmentSlot.MAINHAND, f.player.inventory.getItem(0));
            assertEquals(materialSlot == 0 ? Items.STONE : Items.DIRT, f.player.getMainHandItem().getItem());
            if (hiddenMenu) f.player.containerMenu = new ShulkerBoxMenu(7, f.player.inventory, new SimpleContainer(27));
            assertTrue(f.broker.tryAcquire("quick_shulker",
                    EnumSet.of(ResourceLease.INVENTORY, ResourceLease.CONTAINER, ResourceLease.MAIN_HAND), 0));
            // The lower click hook only protects ContainerSync's own phases. Native hotbar
            // selection and Easy Place interaction do not pass through that hook at all.
            assertFalse(f.sync.guardManualInput());

            assertTrue(manualEntry(entry, f),
                    "Existing inventory/hand material must not let native selection or placement bypass the lease");
            assertFalse(f.coordinator.isBusy());
            assertTrue(f.api.sent.isEmpty());
            assertTrue(f.broker.isResourceHeldByOther(ResourceLease.INVENTORY, "manual_pick"));
            assertTrue(f.broker.isResourceHeld(ResourceLease.CONTAINER));
            assertTrue(f.broker.isResourceHeld(ResourceLease.MAIN_HAND));

            f.broker.releaseOwner("quick_shulker");
            f.player.containerMenu = f.player.inventoryMenu;
            assertFalse(manualEntry(entry, f), "The same manual input must resume when the inventory owner finishes");
        }
    }

    @ParameterizedTest
    @CsvSource({"OPENING, true", "OPENING, false", "RESYNC, true", "RESYNC, false"})
    void failedPickRequestCannotEscapeAStillPendingContainerOperation(String phase, boolean workEnabled) throws Exception {
        try (var f = new Fixture()) {
            f.startSync(phase);
            Configs.Core.WORK_SWITCH.setBooleanValue(workEnabled);
            for (int tick = 0; tick < 300; tick++) f.sync.tick();
            f.assertSyncStillOwnsInventory();

            assertTrue(QuickShulkerBridge.handlePickBlock(f.player, Items.STONE));
            f.tick(10);
            // This input exhausts Take It Out's lease retries and the remaining provider chain.
            // Returning false here lets the native mod dispatch outside the shared broker.
            assertTrue(QuickShulkerBridge.handlePickBlock(f.player, Items.STONE),
                    "UNAVAILABLE cannot allow native extraction while container synchronization owns inventory");
            assertFalse(f.coordinator.isBusy());
            assertEquals(1, f.next.calls);
            assertTrue(f.api.sent.isEmpty());
            f.assertSyncStillOwnsInventory();

            var inventory = f.player.inventoryMenu;
            f.sync.content(0, inventory.getStateId() + 1,
                    inventory.slots.stream().map(slot -> slot.getItem().copy()).toList(), ItemStack.EMPTY);
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            f.useUnavailableProviders();
            assertFalse(QuickShulkerBridge.handlePickBlock(f.player, Items.STONE),
                    "Idle unavailable providers must still leave native pick-block available");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"connection", "level"})
    void changingClientScopeDoesNotLeaveTheNativeFallbackBlocked(String change) throws Exception {
        try (var f = new Fixture()) {
            f.startSync("OPENING");
            f.useUnavailableProviders();
            assertTrue(QuickShulkerBridge.handlePickBlock(f.player, Items.STONE));
            if (change.equals("connection")) {
                set(f.player, LocalPlayer.class, "connection", allocate(CancelledSwapLifecycleTest.Connection.class));
            } else {
                f.client.level = allocate(ClientLevel.class);
            }
            f.sync.tick();
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertFalse(QuickShulkerBridge.handlePickBlock(f.player, Items.STONE));
        }
    }
}
//#endif
