//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.integration.inventory.ChestTrackerAdapter;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static me.aleksilassila.litematica.printer.integration.quickshulker.CancelledSwapLifecycleTest.*;
import static me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerPacketContractTest.allocate;
import static org.junit.jupiter.api.Assertions.*;

class QuickShulkerDeferredScreenTest {
    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    static class Fixture extends ChestTrackerTransferRegressionTest.Fixture {
        final boolean oldWork = Configs.Core.WORK_SWITCH.getBooleanValue();
        final boolean oldQuick = Configs.Placement.QUICK_SHULKER.getBooleanValue();
        final QuickShulkerRequestController requests = (QuickShulkerRequestController) get(
                runtime.quickShulkerAdapter(), QuickShulkerAdapter.class, "requests");
        final QuickShulkerContainerSession session = (QuickShulkerContainerSession) get(
                requests, QuickShulkerRequestController.class, "containerSession");
        final ContainerScreen screen = allocate(ContainerScreen.class);

        Fixture(boolean orderly) throws Exception {
            Configs.Core.WORK_SWITCH.setBooleanValue(true);
            Configs.Placement.QUICK_SHULKER.setBooleanValue(true);
            set(chest, ChestTrackerAdapter.class, "suppressContainerScreen", false);
            set(screen, AbstractContainerScreen.class, "menu", menu);
            var box = QuickShulkerQueryFixture.box(new ItemStack(Items.STONE, 64));
            session.begin(box, true);
            if (orderly) {
                @SuppressWarnings("unchecked")
                var restore = (RestoreSession<OrderedStorageEntry>) get(requests.orderedStorage(),
                        OrderedStorageController.class, "restoreSession");
                restore.schedule(new OrderedStorageEntry(new ItemStack(Items.STONE), box, 0, 10, 2, 0));
                restore.beginContainerWait(40);
            } else {
                set(requests, QuickShulkerRequestController.class, "isOpenHandler", true);
                set(requests, QuickShulkerRequestController.class, "openHandlerTimeout", 1);
            }
            requests.onContainerOpen(7, MenuType.GENERIC_9x3);
        }
        @Override public void close() throws Exception {
            Configs.Core.WORK_SWITCH.setBooleanValue(oldWork);
            Configs.Placement.QUICK_SHULKER.setBooleanValue(oldQuick);
            super.close();
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void matchingExtractionAndReturnStayHiddenAcrossTheFirstRenderFrame(boolean orderly) throws Exception {
        try (var f = new Fixture(orderly)) {
            f.client.setScreen(f.screen);
            assertNull(f.client.screen);
            assertFalse(f.session.owns(7));
            assertFalse(f.requests.shouldSuppressContainerScreen(7));
            assertTrue(f.requests.confirmContainer(7, f.snapshot(f.menu), ItemStack.EMPTY));
            assertNull(f.client.screen);
            assertTrue(f.session.owns(7));
            assertTrue(f.requests.shouldSuppressContainerScreen(7));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"differentContents", "timeout", "cancel"})
    void returnsTheExactDeferredScreenWhenTheOpenIsUnconfirmed(String reason) throws Exception {
        try (var f = new Fixture(false)) {
            f.client.setScreen(f.screen);
            assertNull(f.client.screen);
            switch (reason) {
                case "differentContents" -> {
                    var contents = new java.util.ArrayList<>(f.snapshot(f.menu));
                    contents.set(0, ItemStack.EMPTY);
                    assertFalse(f.requests.confirmContainer(7, contents, ItemStack.EMPTY));
                }
                case "timeout" -> f.requests.tick();
                case "cancel" -> f.requests.resetRuntime();
            }
            assertSame(f.screen, f.client.screen);
            assertFalse(f.player.closed);
            assertTrue(f.connection.packets.isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"scope", "connection", "replacementMenu", "otherScreen"})
    void doesNotReviveAnObsoleteScreen(String change) throws Exception {
        try (var f = new Fixture(false)) {
            f.client.setScreen(f.screen);
            assertNull(f.client.screen);
            switch (change) {
                case "scope" -> f.requests.discardForClientScopeChange();
                case "connection" -> set(f.player, net.minecraft.client.player.LocalPlayer.class, "connection", allocate(Connection.class));
                case "replacementMenu" -> f.player.containerMenu = ChestMenu.threeRows(7, f.player.inventory);
                case "otherScreen" -> {
                    f.client.setScreen(allocate(PauseScreen.class));
                    f.client.setScreen(null);
                }
            }
            f.requests.resetRuntime();
            assertNull(f.client.screen);
            assertFalse(f.player.closed);
        }
    }

    @Test void aDifferentTypeOrMenuInstanceShowsNormally() throws Exception {
        try (var f = new Fixture(false)) {
            f.requests.onContainerOpen(7, MenuType.ANVIL);
            f.client.setScreen(f.screen);
            assertSame(f.screen, f.client.screen);
            f.client.setScreen(null);
            f.requests.onContainerOpen(7, MenuType.GENERIC_9x3);
            f.player.containerMenu = ChestMenu.threeRows(7, f.player.inventory);
            f.client.setScreen(f.screen);
            assertSame(f.screen, f.client.screen);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"menu", "manual", "connection", "confirmedMenu"})
    void matchingContentsDoNotAuthorizeAReplacedCandidate(String change) throws Exception {
        try (var f = new Fixture(false)) {
            f.client.setScreen(f.screen);
            var contents = f.snapshot(f.menu);
            if (change.equals("confirmedMenu")) assertTrue(f.requests.confirmContainer(7, contents, ItemStack.EMPTY));
            switch (change) {
                case "menu", "confirmedMenu" -> f.player.containerMenu = ChestMenu.threeRows(7, f.player.inventory);
                case "manual" -> f.client.setScreen(allocate(PauseScreen.class));
                case "connection" -> set(f.player, net.minecraft.client.player.LocalPlayer.class, "connection", allocate(Connection.class));
            }
            assertFalse(f.requests.confirmContainer(7, contents, ItemStack.EMPTY));
            f.requests.resetRuntime();
            assertFalse(f.player.closed);
            assertTrue(f.connection.packets.isEmpty());
        }
    }

    @Test void screensOpenNormallyWhileTheRuntimeIsAbsentOrInitializing() throws Exception {
        try (var f = new Fixture(false)) {
            set(null, me.aleksilassila.litematica.printer.runtime.RuntimeAccess.class, "current", null);
            f.client.setScreen(f.screen);
            assertSame(f.screen, f.client.screen);
            set(null, me.aleksilassila.litematica.printer.runtime.RuntimeAccess.class, "current",
                    allocate(me.aleksilassila.litematica.printer.runtime.PrinterRuntime.class));
            var pause = allocate(PauseScreen.class);
            f.client.setScreen(pause);
            assertSame(pause, f.client.screen);
            f.client.setScreen(f.screen);
            assertSame(f.screen, f.client.screen);
        }
    }

    @Test void orderlyTimeoutDoesNotCloseAnUnconfirmedMenuReusingAnEarlierId() throws Exception {
        boolean oldOrderly = Configs.Placement.STORE_ORDERLY.getBooleanValue();
        try (var f = new Fixture(true)) {
            Configs.Placement.STORE_ORDERLY.setBooleanValue(true);
            f.requests.setExternalRequestAllowed(true);
            // The server may reuse an ID saved by a previous confirmed return.
            set(f.requests.orderedStorage(), OrderedStorageController.class, "automatedContainerId", 7);
            f.client.setScreen(f.screen);
            assertNull(f.client.screen);
            for (int tick = 0; tick < 40; tick++) f.requests.tick();
            assertFalse(f.player.closed);
            assertSame(f.screen, f.client.screen);
            assertTrue(f.connection.packets.isEmpty());
        } finally {
            Configs.Placement.STORE_ORDERLY.setBooleanValue(oldOrderly);
        }
    }
}
//#endif
