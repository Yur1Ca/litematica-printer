//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.util.Arrays;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerQueryFixture.box;
import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderedStorageDestinationTest {
    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    @Test void aKnownFullOriginalIsSkippedEvenWhenItsOtherSlotsMatchExactly() throws Exception {
        try (var f = new QuickShulkerQueryFixture(false)) {
            var borrowed = new ItemStack(Items.PISTON, 32);
            var full = new ItemStack[27];
            Arrays.fill(full, new ItemStack(Items.STONE, 64));
            var original = full.clone();
            original[0] = borrowed;
            var entry = new OrderedStorageEntry(borrowed, box(original), 0, 9, 2, 0);
            f.inventory(2, borrowed);
            f.slot(9, box(full));
            assertEquals(-1, OrderedStorageStacks.findBestShulkerMenuSlot(f.player, entry));
            f.slot(10, box());
            assertEquals(10, OrderedStorageStacks.findBestShulkerMenuSlot(f.player, entry));
        }
    }

    @Test void originalTrailingSlotRemainsAvailableAfterExtractingItsLastStack() throws Exception {
        try (var f = new QuickShulkerQueryFixture(false)) {
            var borrowed = new ItemStack(Items.PISTON, 32);
            var original = new ItemStack[27];
            Arrays.fill(original, ItemStack.EMPTY);
            original[0] = new ItemStack(Items.STONE);
            original[26] = borrowed;
            var entry = new OrderedStorageEntry(borrowed, box(original), 26, 10, 2, 0);
            f.inventory(2, borrowed);
            f.slot(10, box(original[0]));
            original[0] = new ItemStack(Items.DIRT);
            f.slot(9, box(original));
            assertEquals(10, OrderedStorageStacks.findBestShulkerMenuSlot(f.player, entry));
        }
    }

    @Test void aBorrowedBoxCannotBeItsOwnReturnDestination() throws Exception {
        try (var f = new QuickShulkerQueryFixture(false)) {
            var borrowed = box();
            var entry = new OrderedStorageEntry(borrowed, box(borrowed), 0, 10, 2, 0);
            f.inventory(2, borrowed);
            assertEquals(-1, OrderedStorageStacks.findBestShulkerMenuSlot(f.player, entry));
        }
    }
}
//#endif
