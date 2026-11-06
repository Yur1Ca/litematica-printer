//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.lang.reflect.InvocationTargetException;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** A rejected window-0 click must not masquerade as a successful material switch. */
class InventoryMenuSelectionRegressionTest {
    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void aBedInTheBackpackWaitsWithoutClickingAnotherOpenMenu(boolean matching) throws Exception {
        try (var fixture = new MaterialSelectionFixture()) {
            var bed = new ItemStack(Items.WHITE_BED);
            fixture.player.inventory.setItem(10, bed);
            var chest = ChestMenu.threeRows(68, fixture.player.inventory);
            fixture.player.containerMenu = chest;

            assertFalse(matching
                    ? fixture.matching(stack -> stack.is(Items.WHITE_BED), bed, -1)
                    : fixture.items(new Item[]{Items.WHITE_BED}, -1));

            assertTrue(fixture.traffic.isEmpty(), "Do not select a pick slot or submit a window-0 swap while another menu is open");
            assertFalse(fixture.guard.isWaiting(), "Vanilla discards a mismatching-menu click; it cannot start switch confirmation");
            assertTrue(fixture.requests.isEmpty(), "The bed already exists and must not trigger another restock");
            assertSame(bed, fixture.player.inventory.getItem(10));
            assertSame(chest, fixture.player.containerMenu, "A material lookup must not close a player's container");
        }
    }

    @Test
    void directMainHandSwapCannotBypassTheMenuBoundary() throws Exception {
        try (var fixture = new MaterialSelectionFixture()) {
            var bed = new ItemStack(Items.WHITE_BED);
            fixture.player.inventory.setItem(10, bed);
            var chest = ChestMenu.threeRows(68, fixture.player.inventory);
            fixture.player.containerMenu = chest;

            assertFalse(callInventoryUtility(fixture, "swapItemToMainHand", bed));
            assertTrue(fixture.traffic.isEmpty());
            assertSame(chest, fixture.player.containerMenu);
            assertSame(bed, fixture.player.inventory.getItem(10));
        }
    }

    @Test
    void offhandSwapCannotSubmitAWindowZeroClickToAnotherMenu() throws Exception {
        try (var fixture = new MaterialSelectionFixture()) {
            var bed = new ItemStack(Items.WHITE_BED);
            fixture.player.inventory.setItem(10, bed);
            var chest = ChestMenu.threeRows(68, fixture.player.inventory);
            fixture.player.containerMenu = chest;

            assertFalse(callInventoryUtility(fixture, "setItemToOffhand", bed));
            assertTrue(fixture.traffic.isEmpty());
            assertSame(chest, fixture.player.containerMenu);
            assertTrue(fixture.player.inventory.getItem(40).isEmpty());
        }
    }

    @Test
    void hotbarSelectionStillWorksWithoutMovingItemsBetweenSlots() throws Exception {
        try (var fixture = new MaterialSelectionFixture()) {
            var bed = new ItemStack(Items.WHITE_BED);
            fixture.player.inventory.setItem(3, bed);
            var chest = ChestMenu.threeRows(68, fixture.player.inventory);
            fixture.player.containerMenu = chest;

            assertTrue(fixture.items(new Item[]{Items.WHITE_BED}, -1));
            assertEquals(java.util.List.of("select:3"), fixture.traffic);
            assertFalse(fixture.guard.isWaiting());
            assertSame(bed, fixture.player.getMainHandItem());
            assertSame(chest, fixture.player.containerMenu);
        }
    }

    @Test
    void closingTheExternalMenuAllowsTheSameBedToBeSelectedNormally() throws Exception {
        try (var fixture = new MaterialSelectionFixture()) {
            var bed = new ItemStack(Items.WHITE_BED);
            fixture.player.inventory.setItem(10, bed);
            fixture.player.containerMenu = ChestMenu.threeRows(68, fixture.player.inventory);
            assertFalse(fixture.items(new Item[]{Items.WHITE_BED}, -1));
            assertTrue(fixture.traffic.isEmpty());

            fixture.player.containerMenu = fixture.player.inventoryMenu;
            assertFalse(fixture.items(new Item[]{Items.WHITE_BED}, -1));
            assertEquals(java.util.List.of("select:2", "swap:10:2"), fixture.traffic);
            assertTrue(fixture.guard.isWaiting());
            fixture.player.inventory.setItem(10, ItemStack.EMPTY);
            fixture.player.inventory.setItem(2, bed);
            fixture.tick++;

            assertTrue(fixture.items(new Item[]{Items.WHITE_BED}, -1));
            assertFalse(fixture.guard.isWaiting());
            assertTrue(fixture.requests.isEmpty());
            assertSame(bed, fixture.player.getMainHandItem());
        }
    }

    private static boolean callInventoryUtility(MaterialSelectionFixture fixture, String name, ItemStack stack) throws Exception {
        Class<?> utilities = fixture.selector.getClassLoader().loadClass(
                "me.aleksilassila.litematica.printer.utils.InventoryUtils");
        try {
            return (boolean) utilities.getMethod(name, ItemStack.class, Minecraft.class).invoke(null, stack, fixture.client);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) throw cause;
            if (exception.getCause() instanceof Error cause) throw cause;
            throw exception;
        }
    }
}
//#endif
