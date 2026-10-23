//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerQueryFixture.box;

class QuickShulkerQueryTest {
    @BeforeAll static void bootstrap() throws Exception { CancelledSwapLifecycleTest.bootstrap(); }

    @Test void missingCandidatesDecodeEachCarriedBoxAtMostOncePerQuery() throws Exception {
        try (var f = new QuickShulkerQueryFixture(true)) {
            f.slot(9, box(new ItemStack(Items.STONE)));
            f.slot(10, box(new ItemStack(Items.STONE)));
            f.slot(11, box(new ItemStack(Items.STONE)));
            assertFalse(f.query(List.of(Items.DIRT, Items.GLASS, Items.DIAMOND), false));
            assertEquals(3, f.decodes, "Changing the requested item does not change the same carried box contents");
            assertTrue(f.attempts.isEmpty());
        }
    }

    @Test void candidatePriorityPrecedesSlotPriorityAndFailedOpenDoesNotStopFallback() throws Exception {
        try (var f = new QuickShulkerQueryFixture(true)) {
            f.slot(9, box(new ItemStack(Items.DIRT)));
            f.slot(10, box(new ItemStack(Items.STONE)));
            f.slot(11, box(new ItemStack(Items.STONE), new ItemStack(Items.DIRT)));
            assertFalse(f.query(List.of(Items.STONE, Items.DIRT), false));
            assertEquals(List.of(10, 11, 9, 11), f.attempts);
        }
    }

    @Test void filledNestedBoxesAndNonSingleOuterStacksCannotSatisfyAnEmptyBoxRequest() throws Exception {
        try (var f = new QuickShulkerQueryFixture(true)) {
            f.slot(9, box(box(new ItemStack(Items.STONE))));
            f.slot(10, box(new ItemStack(Items.SHULKER_BOX)));
            var invalid = box(new ItemStack(Items.SHULKER_BOX)); invalid.setCount(2); f.slot(11, invalid);
            assertFalse(f.query(List.of(Items.SHULKER_BOX), true));
            assertEquals(List.of(10), f.attempts);
        }
    }

    @Test void aLaterQueryReadsMutatedComponentsAndTheCurrentInventory() throws Exception {
        try (var f = new QuickShulkerQueryFixture(true)) {
            var carried = box(new ItemStack(Items.STONE)); f.slot(9, carried);
            f.query(List.of(Items.DIRT, Items.GLASS), false);
            assertTrue(f.attempts.isEmpty());
            carried.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIRT))));
            f.query(List.of(Items.DIRT), false);
            assertEquals(List.of(9), f.attempts);
            f.slot(9, ItemStack.EMPTY); f.slot(12, box(new ItemStack(Items.GLASS)));
            f.attempts.clear(); f.query(List.of(Items.GLASS), false);
            assertEquals(List.of(12), f.attempts);
        }
    }

    @Test void anOptionalModOpenAttemptInvalidatesEarlierReadContents() throws Exception {
        try (var f = new QuickShulkerQueryFixture(true)) {
            var carried = box(new ItemStack(Items.STONE)); f.slot(9, carried);
            f.onOpen = () -> carried.set(DataComponents.CONTAINER,
                    ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIRT))));
            f.query(List.of(Items.STONE, Items.DIRT), false);
            assertEquals(List.of(9, 9), f.attempts, "A failed third-party open can have changed stack components");
        }
    }

    @Test void aThrowingOptionalModCannotLeaveStaleContentsForTheNextCandidate() throws Exception {
        try (var f = new QuickShulkerQueryFixture(true)) {
            var carried = box(new ItemStack(Items.STONE)); f.slot(9, carried);
            f.onOpen = () -> {
                carried.set(DataComponents.CONTAINER,
                        ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIRT))));
                throw new IllegalStateException("Optional mod failed after changing box contents");
            };
            assertFalse(f.query(List.of(Items.STONE, Items.DIRT), false));
            assertEquals(List.of(9, 9), f.attempts,
                    "An exception must not hide the newly available next candidate");
        }
    }
}
//#endif
