//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerQueryFixture.box;

class QuickShulkerTargetSelectionTest {
    @BeforeAll static void bootstrap() throws Exception { CancelledSwapLifecycleTest.bootstrap(); }

    @Test void laterConfiguredEmptyHotbarWinsBeforeClearingAnOccupiedSlot() throws Exception {
        try (var f = new QuickShulkerQueryFixture(false)) {
            f.configuredSlots("1,2");
            f.inventory(0, new ItemStack(Items.DIRT));
            f.extract();
            assertEquals(List.of(1), f.clickedHotbar);
            assertEquals(List.of(0), f.clickedSource, "Extract directly instead of clearing hotbar first");
        }
    }

    @Test void occupiedConfiguredSlotStillUsesAnEmptyMainInventorySlot() throws Exception {
        try (var f = new QuickShulkerQueryFixture(false)) {
            f.configuredSlots("1");
            f.inventory(0, new ItemStack(Items.DIRT));
            f.extract();
            assertEquals(List.of(0), f.clickedHotbar);
            assertEquals(List.of(27), f.clickedSource);
        }
    }

    @Test void fullInventoryCanExchangeOrdinaryMaterialWithTheSourceBox() throws Exception {
        try (var f = new QuickShulkerQueryFixture(false)) {
            f.configuredSlots("1");
            for (int i = 0; i < 36; i++) f.inventory(i, new ItemStack(Items.DIRT, 64));
            f.slot(35, box(new ItemStack(Items.STONE)));
            f.query(List.of(Items.STONE), false);
            assertEquals(List.of(35), f.attempts);
            f.extract();
            assertEquals(List.of(0), f.clickedSource);
            assertEquals(List.of(0), f.clickedHotbar);
        }
    }

    @Test void unconfiguredEmptyHotbarCannotOpenUntilConfigurationChanges() throws Exception {
        try (var f = new QuickShulkerQueryFixture(false)) {
            f.configuredSlots("1");
            for (int i = 0; i < 36; i++) f.inventory(i, box(new ItemStack(Items.DIRT, 64)));
            f.slot(35, box(new ItemStack(Items.STONE)));
            f.inventory(1, ItemStack.EMPTY);
            f.query(List.of(Items.STONE), false);
            assertTrue(f.attempts.isEmpty());
            f.configuredSlots("1,2");
            f.query(List.of(Items.STONE), false);
            assertEquals(List.of(35), f.attempts);
        }
    }

    @Test void protectedToolAndSourceBoxNeverBecomeDestinations() throws Exception {
        try (var f = new QuickShulkerQueryFixture(false)) {
            boolean old = fi.dy.masa.litematica.config.Configs.Generic.PICK_BLOCK_AVOID_TOOLS.getBooleanValue();
            try {
                fi.dy.masa.litematica.config.Configs.Generic.PICK_BLOCK_AVOID_TOOLS.setBooleanValue(true);
                f.configuredSlots("1,2");
                f.inventory(0, new ItemStack(Items.DIAMOND_PICKAXE));
                f.inventory(1, box(new ItemStack(Items.STONE)));
                f.query(List.of(Items.STONE), false);
                assertTrue(f.attempts.isEmpty());
            } finally {
                fi.dy.masa.litematica.config.Configs.Generic.PICK_BLOCK_AVOID_TOOLS.setBooleanValue(old);
            }
        }
    }

    @Test void sourceHotbarSlotStaysProtectedEvenWhenOpeningTemporarilyEmptiesIt() throws Exception {
        try (var f = new QuickShulkerQueryFixture(false)) {
            f.configuredSlots("2");
            f.inventory(1, ItemStack.EMPTY);
            f.extract(37);
            assertTrue(f.clickedHotbar.isEmpty());
            assertTrue(f.player.closed);
        }
    }

    @Test void extractionRechecksTargetsChangedAfterTheOpeningAttempt() throws Exception {
        try (var f = new QuickShulkerQueryFixture(false)) {
            f.configuredSlots("1");
            f.slot(35, box(new ItemStack(Items.STONE)));
            f.query(List.of(Items.STONE), false);
            assertEquals(List.of(35), f.attempts);
            for (int i = 0; i < 36; i++) f.inventory(i, new ItemStack(Items.DIRT, 64));
            f.slot(35, box(new ItemStack(Items.STONE)));
            f.extract();
            assertEquals(List.of(0), f.clickedSource);
            assertEquals(List.of(0), f.clickedHotbar);
        }
    }
}
//#endif
