//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.enums.QuickShulkerModeType;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockEngine;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockTarget;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation;
import me.aleksilassila.litematica.printer.integration.litematica.LitematicaAdapter;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import me.aleksilassila.litematica.printer.utils.mods.ModLoadUtils;
import me.aleksilassila.litematica.printer.utils.mods.QuickShulkerBridge;
import me.aleksilassila.litematica.printer.utils.mods.ShulkerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static me.aleksilassila.litematica.printer.integration.quickshulker.CancelledSwapLifecycleTest.*;
import static me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerPacketContractTest.allocate;
import static org.junit.jupiter.api.Assertions.*;

/** Actual runtime bridge/provider paths, with only the server's replies supplied by the test. */
class BedrockOrderedStorageBoundaryTest {
    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    static class Level extends QuickShulkerPacketContractTest.AuditLevel {
        long tick;
        @Override public long getGameTime() { return tick; }
    }

    static class Fixture extends OrderedStorageDrainTest.Fixture {
        final Level level = allocate(Level.class);
        final IConfigOptionListEntry oldMode = Configs.Placement.QUICK_SHULKER_MODE.getOptionListValue();
        final int oldCloseScreen = ModLoadUtils.closeScreen;
        final Object oldShulkerClient;
        final BedrockEngine bedrock;

        Fixture() throws Exception {
            super();
            Class.forName(ShulkerUtils.class.getName());
            oldShulkerClient = get(null, ShulkerUtils.class, "client");
            staticClient(client);
            ShulkerUtils.clearOpenAttempt();
            ModLoadUtils.closeScreen = 0;
            Configs.Placement.QUICK_SHULKER_MODE.setOptionListValue(QuickShulkerModeType.CLICK_SLOT);
            client.level = level;
            player.auditLevel = level;
            bedrock = new BedrockEngine(client, new CooldownUtils(), () -> level.tick, new LitematicaAdapter());
            set(runtime, PrinterRuntime.class, "bedrockEngine", bedrock);
        }

        void unopened() throws Exception {
            storage.reset();
            player.containerMenu = player.inventoryMenu;
            var box = new ItemStack(Items.SHULKER_BOX);
            player.inventory.setItem(10, box);
            storage.newItem(new ItemStack(Items.PISTON, 64), box, 0, 10, 9);
            level.tick = 500;
        }

        @SuppressWarnings("unchecked") List<BedrockTarget> targets() throws Exception {
            var registry = get(bedrock, BedrockEngine.class, "targets");
            return (List<BedrockTarget>) get(registry, registry.getClass(), "targets");
        }

        void active(String kind) throws Exception {
            if (kind.equals("target")) {
                targets().add(allocate(BedrockTarget.class));
            } else {
                var sync = get(bedrock, BedrockEngine.class, "networkSync");
                var begin = sync.getClass().getDeclaredMethod("beginAttempt", BlockPos.class, Set.class);
                begin.setAccessible(true);
                begin.invoke(sync, BlockPos.ZERO, Set.of(BlockPos.ZERO.above()));
            }
        }

        @SuppressWarnings("unchecked") List<OrderedStorageEntry> tracked() throws Exception {
            return (List<OrderedStorageEntry>) get(storage, OrderedStorageController.class, "trackedItems");
        }

        @SuppressWarnings("unchecked") RestoreSession<OrderedStorageEntry> session() throws Exception {
            return (RestoreSession<OrderedStorageEntry>) get(storage, OrderedStorageController.class, "restoreSession");
        }

        @SuppressWarnings("removal") static void staticClient(Object client) throws Exception {
            var access = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            access.setAccessible(true);
            var unsafe = (sun.misc.Unsafe) access.get(null);
            var field = ShulkerUtils.class.getDeclaredField("client");
            unsafe.putObject(unsafe.staticFieldBase(field), unsafe.staticFieldOffset(field), client);
        }

        @Override public void close() throws Exception {
            ShulkerUtils.clearOpenAttempt();
            staticClient(oldShulkerClient);
            Configs.Placement.QUICK_SHULKER_MODE.setOptionListValue(oldMode);
            ModLoadUtils.closeScreen = oldCloseScreen;
            super.close();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"target", "network"})
    void runtimeMaintenanceCannotOpenABoxDuringBedrockWork(String kind) throws Exception {
        try (var f = new Fixture()) {
            f.unopened();
            f.active(kind);
            QuickShulkerBridge.onTick();
            assertEquals(0, f.connection.packets.size(), "Active bedrock work cannot start an automatic return");
            assertFalse(f.storage.hasPendingRestore());
            assertFalse(QuickShulkerBridge.shouldPause());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertEquals(1, f.tracked().size(), "Deferral must preserve the borrowed stack's return record");
        }
    }

    @Test void providerPressureRecoveryCannotStartAReturnDuringBedrockWork() throws Exception {
        try (var f = new Fixture()) {
            f.unopened();
            f.active("target");
            for (int slot = 0; slot < 36; slot++) {
                if (slot != 9 && slot != 10) f.player.inventory.setItem(slot, new ItemStack(Items.STONE, 64));
            }
            var request = MaterialRequest.single(91, Items.REDSTONE_TORCH, 1, MaterialRequest.Source.BEDROCK);
            assertEquals(MaterialReservation.State.UNAVAILABLE, f.adapter.request(request).state());
            assertEquals(0, f.connection.packets.size(), "Inventory pressure cannot interrupt a bedrock attempt");
            assertFalse(f.storage.hasPendingRestore());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertEquals(1, f.tracked().size());
        }
    }

    @Test void activeBedrockRefreshesTheIdleReturnDelayWithoutMainHandCallbacks() throws Exception {
        try (var f = new Fixture()) {
            f.unopened();
            f.active("target");
            QuickShulkerBridge.onTick();
            assertEquals(0, f.connection.packets.size());
            f.targets().clear();
            f.level.tick = 599;
            QuickShulkerBridge.onTick();
            assertEquals(0, f.connection.packets.size(), "The idle delay starts after bedrock activity");
            f.level.tick = 600;
            QuickShulkerBridge.onTick();
            assertEquals(1, f.connection.packets.size());
            assertTrue(f.storage.isWaitingForRestoreContainer());
        }
    }

    @Test void deferredUnopenedReturnReleasesItsLeaseAndKeepsTheTrackedStack() throws Exception {
        try (var f = new Fixture()) {
            f.unopened();
            f.session().schedule(f.tracked().getFirst());
            f.active("target");
            QuickShulkerBridge.onTick();
            assertEquals(0, f.connection.packets.size());
            assertFalse(f.storage.hasPendingRestore());
            assertFalse(QuickShulkerBridge.shouldPause());
            assertFalse(f.broker.isResourceHeld(ResourceLease.MAIN_HAND));
            assertEquals(1, f.tracked().size());
        }
    }

    @Test void bedrockActivityStillRefreshesWhenItsInventoryLeaseBlocksMaintenance() throws Exception {
        try (var f = new Fixture()) {
            f.unopened();
            f.active("target");
            assertTrue(f.broker.tryAcquire("bedrock", EnumSet.of(ResourceLease.MAIN_HAND), 0L));
            QuickShulkerBridge.onTick();
            f.broker.releaseOwner("bedrock");
            f.targets().clear();
            f.level.tick = 599;
            QuickShulkerBridge.onTick();
            assertEquals(0, f.connection.packets.size(), "Activity must be observed before lease acquisition");
            assertFalse(f.storage.hasPendingRestore());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
        }
    }

    @Test void alreadyDispatchedReturnDrainsSafelyWhileBedrockIsActive() throws Exception {
        try (var f = new Fixture()) {
            f.storage.restorePendingItem();
            f.active("target");
            f.adapter.tick();
            assertTrue(f.storage.isDraining());
            f.assertExclusion();
            f.reply(f.menu, 1, List.of(ItemStack.EMPTY, ItemStack.EMPTY), new ItemStack(Items.PISTON, 64));
            assertEquals(2, f.connection.packets.size());
            assertFalse(f.player.closed);
            f.reply(f.menu, 2, List.of(new ItemStack(Items.PISTON, 64), ItemStack.EMPTY), ItemStack.EMPTY);
            assertTrue(f.player.closed);
            assertFalse(f.storage.hasPendingRestore());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
        }
    }

    @Test void alreadyOpenedReturnIsPreservedWhenBedrockBecomesActive() throws Exception {
        try (var f = new Fixture()) {
            f.active("target");
            f.adapter.tick();
            assertTrue(f.storage.isWaitingForRestoreContainer());
            f.storage.restorePendingItem();
            assertTrue(f.storage.isDraining());
            assertEquals(1, f.connection.packets.size());
            assertFalse(f.player.closed);
            f.assertExclusion();
        }
    }
}
//#endif
