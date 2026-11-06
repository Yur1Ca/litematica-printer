//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import me.aleksilassila.litematica.printer.handler.FeatureModuleBase;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Material readiness must not inherit a missing-stock delay after a temporary hand/menu wait. */
class TrenchFillMaterialResumeTest {
    private static final BlockPos TARGET = new BlockPos(0, 1, 0);

    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    @Test
    void aOneTickHandConfirmationDoesNotParkTheFillTargetForOneSecond() throws Exception {
        try (var f = new TrenchFillIntegrationTest.Fixture()) {
            f.player.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
            f.switchGuard.markSwitchIfNeeded(Items.COBBLESTONE);

            assertTrue(f.execute());
            assertTrue(f.queued.isEmpty());
            assertTrue(f.requests.isEmpty());
            assertTrue(f.canAttempt(101), "Only hand confirmation is pending; the material is already held");

            f.level.tick = 101;
            assertFalse(f.execute());
            assertEquals(1, f.queued.size());
            assertTrue(f.requests.isEmpty());
        }
    }

    @Test
    void arrivingFillMaterialImmediatelyWakesItsMissingStockTarget() throws Exception {
        try (var f = new TrenchFillIntegrationTest.Fixture()) {
            f.player.inventory.setItem(0, new ItemStack(Items.SAND, 64));
            assertTrue(f.execute());
            assertEquals(1, f.requests.size());
            assertFalse(f.canAttempt(119), "A genuinely missing material retains bounded retry backoff");

            f.level.tick = 101;
            f.player.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
            inventoryChanged(f);

            assertTrue(f.canAttempt(101), "Confirmed inventory gain should not wait out the old missing-stock deadline");
            assertFalse(f.execute());
            assertEquals(1, f.queued.size());
            assertEquals(1, f.requests.size(), "Resuming a filled inventory must not ask for another delivery");
        }
    }

    @Test
    void inventoryGainDoesNotReleaseANonMaterialRetry() throws Exception {
        try (var f = new TrenchFillIntegrationTest.Fixture()) {
            defer(f, TARGET, 110);
            f.level.tick = 101;
            f.player.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
            inventoryChanged(f);

            assertFalse(f.canAttempt(101), "Support/action retry deadlines are independent of material arrivals");
            assertFalse(f.canAttempt(109));
            assertTrue(f.canAttempt(110));
        }
    }

    @Test
    void inventoryGainDoesNotReleaseAnUnconfirmedFillPlacement() throws Exception {
        try (var f = new TrenchFillIntegrationTest.Fixture()) {
            f.player.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
            assertFalse(f.execute());
            assertEquals(1, f.queued.size());

            f.level.tick = 101;
            inventoryChanged(f);

            assertFalse(f.canAttempt(101));
            assertFalse(f.canAttempt(130), "Material readiness cannot replace the server's block confirmation");
            assertEquals(1, f.queued.size());
        }
    }

    @Test
    void anotherOpenMenuCausesOnlyATemporaryWaitWithoutRestocking() throws Exception {
        try (var f = new TrenchFillIntegrationTest.Fixture()) {
            f.player.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
            var menu = ChestMenu.threeRows(68, f.player.inventory);
            f.player.containerMenu = menu;

            assertTrue(f.execute());
            assertTrue(f.queued.isEmpty(), "Do not send a fill while another menu owns player interaction");
            assertTrue(f.requests.isEmpty(), "The fill material is already available");
            assertSame(menu, f.player.containerMenu, "Trench filling must not close the player's menu");
            assertTrue(f.canAttempt(101), "A menu wait must not be stored as missing stock for twenty ticks");

            f.player.containerMenu = f.player.inventoryMenu;
            f.level.tick = 101;
            assertFalse(f.execute());
            assertEquals(1, f.queued.size());
            assertTrue(f.requests.isEmpty());
        }
    }

    private static void inventoryChanged(TrenchFillIntegrationTest.Fixture fixture) throws Exception {
        var method = FeatureModuleBase.class.getDeclaredMethod("onInventoryAvailabilityChanged");
        method.setAccessible(true);
        method.invoke(fixture.handler);
    }

    private static void defer(TrenchFillIntegrationTest.Fixture fixture, BlockPos pos, long retryTick) throws Exception {
        var method = fixture.state.getClass().getDeclaredMethod("defer", BlockPos.class, long.class);
        method.setAccessible(true);
        method.invoke(fixture.state, pos, retryTick);
    }
}
//#endif
