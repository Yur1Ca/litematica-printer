//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.containersync;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static me.aleksilassila.litematica.printer.integration.containersync.ContainerSyncControllerTest.*;
import static org.junit.jupiter.api.Assertions.*;

class ContainerSyncScreenTest {
    @BeforeAll static void bootstrap() { ContainerSyncPlanTest.bootstrap(); }

    static class Client extends Minecraft {
        Client() { super(null); }
        @Override public void setScreen(Screen screen) { this.screen = screen; }
    }

    static class ScreenFixture extends Fixture {
        final Client recording = allocate(Client.class);
        final Inventory inventory = new Inventory(client.player, new EntityEquipment());
        final ChestMenu owned = ChestMenu.threeRows(7, inventory);
        final ContainerScreen screen = allocate(ContainerScreen.class);

        ScreenFixture() throws Exception {
            recording.player = client.player;
            set(controller, ContainerSyncController.class, "client", recording);
            set(recording.player, Player.class, "inventory", inventory);
            recording.player.containerMenu = owned;
            set(screen, AbstractContainerScreen.class, "menu", owned);
            set(controller, ContainerSyncController.class, "layout", ContainerSyncLayout.CHEST);
        }
    }

    @Test void onlyTheExactRequestedMenuIsHiddenAndForegroundScreensSurviveItsClose() throws Exception {
        try (var f = new ScreenFixture()) {
            f.phase("READY");
            assertFalse(f.controller.suppressScreen(f.screen));
            f.phase("CONTENT");
            f.recording.player.containerMenu = ChestMenu.threeRows(7, f.inventory);
            assertFalse(f.controller.suppressScreen(f.screen), "A replacement can reuse the same menu ID");
            f.recording.player.containerMenu = f.owned;
            assertTrue(f.controller.suppressScreen(f.screen));
            assertTrue(f.controller.suppressScreen(allocate(InventoryScreen.class)), "Menu-0 clicks cannot target a background chest");
            assertFalse(f.controller.suppressScreen(null), "A manual close remains available outside automatic cleanup");
            f.recording.screen = allocate(PauseScreen.class);
            set(f.controller, ContainerSyncController.class, "closing", true);
            assertTrue(f.controller.suppressScreen(null), "Closing the hidden chest must preserve the foreground screen");
        }
    }

    @Test void anUnexpectedInitialCursorRestoresTheRealChestForManualRecovery() throws Exception {
        try (var f = new ScreenFixture()) {
            assertTrue(f.controller.suppressScreen(f.screen));
            f.owned.setCarried(ContainerSyncPlanTest.stone(3));
            f.controller.content(7, 1, f.owned.slots.stream().map(slot -> slot.getItem()).toList(), f.owned.getCarried());
            assertFalse(f.otherInventoryOwner());
            f.controller.tick();
            f.controller.content(7, 2, f.owned.slots.stream().map(slot -> slot.getItem()).toList(), f.owned.getCarried());
            f.controller.tick();
            assertNull(f.recording.screen, "Keep the menu hidden until the server confirms this cursor");
            f.ack();
            assertSame(f.screen, f.recording.screen);
            assertSame(f.owned, f.recording.player.containerMenu);
            assertFalse(f.controller.guardManualInput(), "Allow the recovery click into the original chest");
        }
    }

    @Test void aManualRightClickOnlyWaitsWhenTheAutomaticOpenIsAlreadyInFlight() throws Exception {
        try (var f = new ScreenFixture()) {
            f.phase("OPENING");
            assertTrue(f.controller.guardManualUse());
            f.phase("READY");
            assertFalse(f.controller.guardManualUse());
            assertTrue(f.otherInventoryOwner());
            assertFalse(f.controller.guardManualUse());
        }
    }

    @Test void theHotkeyCanHandBackAnUnacknowledgedChestWithoutSendingAnotherClick() throws Exception {
        try (var f = new ScreenFixture()) {
            assertTrue(f.controller.suppressScreen(f.screen));
            var port = f.startTransfer("pickup");
            var field = ContainerSyncController.class.getDeclaredField("transfer");
            field.setAccessible(true);
            ((ContainerSyncTransfer) field.get(f.controller)).tick(6000, true);
            f.controller.toggle();
            assertSame(f.screen, f.recording.screen);
            assertSame(f.owned, f.recording.player.containerMenu);
            assertTrue(f.otherInventoryOwner());
            f.controller.content(7, 3, f.owned.slots.stream().map(slot -> slot.getItem()).toList(), f.owned.getCarried());
            f.controller.tick();
            assertEquals(java.util.List.of(1), port.clicks);
            assertTrue(f.listener.packets.isEmpty(), "Taking over must not close the menu or send a recovery click");
        }
    }
}
//#endif
