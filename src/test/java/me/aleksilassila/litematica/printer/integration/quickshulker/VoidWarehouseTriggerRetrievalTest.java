//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.util.List;
import me.aleksilassila.litematica.printer.integration.inventory.ChestTrackerAdapter;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.VoidWarehouseTriggerItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static me.aleksilassila.litematica.printer.integration.quickshulker.CancelledSwapLifecycleTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Manual retrieval may carry a trigger, while all material selection still needs a usable table. */
class VoidWarehouseTriggerRetrievalTest {
    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    private static ItemStack trigger(String name) {
        var stack = new ItemStack(Items.CRAFTING_TABLE);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }

    private static MaterialRequest request(MaterialRequest.Source source, int count) {
        return MaterialRequest.single(72, Items.CRAFTING_TABLE, count, source);
    }

    /** The shared fixture starts after the request/open boundary, before the first server content. */
    private static void configureRequest(ChestTrackerTransferRegressionTest.Fixture f,
                                         MaterialRequest request, ItemStack exactStack) throws Exception {
        set(f.chest, ChestTrackerAdapter.class, "activeRequest", request);
        set(f.chest, ChestTrackerAdapter.class, "requestedItem", Items.CRAFTING_TABLE);
        set(f.chest, ChestTrackerAdapter.class, "requestedItems", List.of(Items.CRAFTING_TABLE));
        set(f.chest, ChestTrackerAdapter.class, "requestedStack", exactStack.copy());
        set(f.chest, ChestTrackerAdapter.class, "exactMatch", !exactStack.isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"虚空仓库", "  VOID WAREHOUSE  "})
    void manualScreenRetrievesTheExactTriggerAndConfirmsItInInventory(String name) throws Exception {
        try (var f = new ChestTrackerTransferRegressionTest.Fixture()) {
            var wanted = trigger(name);
            var manual = request(MaterialRequest.Source.CHEST_TRACKER_SCREEN, 1);
            configureRequest(f, manual, wanted);
            f.menu.slots.get(0).set(new ItemStack(Items.CRAFTING_TABLE));
            f.menu.slots.get(1).set(wanted);

            f.initial();
            assertEquals(1, f.connection.packets.size(), "The manual request must initiate the take");
            assertEquals(1, ((ServerboundContainerClickPacket) f.connection.packets.getFirst()).slotNum(),
                    "Exact retrieval must skip the unnamed table and take the user's named stack");
            assertFalse(f.player.closed, "Wait for the server instead of trusting the outgoing click");
            assertEquals(MaterialReservation.State.PENDING, f.chest.status(manual).state());

            f.menu.quickMoveStack(f.player, 1);
            f.reply(f.menu, 2, f.snapshot(f.menu), ItemStack.EMPTY);

            assertTrue(f.player.closed);
            assertTrue(ItemStack.isSameItemSameComponents(wanted, f.player.inventory.getItem(8)));
            assertTrue(VoidWarehouseTriggerItem.isTrigger(f.player.inventory.getItem(8)));
            assertEquals(MaterialReservation.State.AVAILABLE, f.chest.status(manual).state(),
                    "Both hasRequestedItem and finishAvailable must recognize the confirmed manual take");
        }
    }

    @Test void printingSkipsTheTriggerAndRetrievesThePlainTable() throws Exception {
        try (var f = new ChestTrackerTransferRegressionTest.Fixture()) {
            var printing = request(MaterialRequest.Source.PRINT, 1);
            configureRequest(f, printing, ItemStack.EMPTY);
            var warehouse = trigger("虚空仓库");
            f.menu.slots.get(0).set(warehouse);
            f.menu.slots.get(1).set(new ItemStack(Items.CRAFTING_TABLE));

            f.initial();
            assertEquals(1, f.connection.packets.size());
            assertEquals(1, ((ServerboundContainerClickPacket) f.connection.packets.getFirst()).slotNum(),
                    "Printing must leave the trigger in the chest even when it appears first");
            f.menu.quickMoveStack(f.player, 1);
            f.reply(f.menu, 2, f.snapshot(f.menu), ItemStack.EMPTY);

            assertTrue(ItemStack.isSameItemSameComponents(warehouse, f.menu.slots.get(0).getItem()));
            assertTrue(f.player.inventory.getItem(8).is(Items.CRAFTING_TABLE));
            assertFalse(VoidWarehouseTriggerItem.isTrigger(f.player.inventory.getItem(8)));
            assertEquals(MaterialReservation.State.AVAILABLE, f.chest.status(printing).state());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void materialSelectionRejectsTheTriggerAndCanSelectAPlainTable(boolean matching) throws Exception {
        try (var f = new MaterialSelectionFixture()) {
            var warehouse = trigger("虚空仓库");
            f.player.inventory.setItem(0, warehouse);

            assertFalse(selectTable(f, matching));
            assertTrue(f.traffic.isEmpty(), "A trigger already in hand must not authorize material use");
            if (matching) {
                assertTrue(f.requests.isEmpty(), "A stack predicate alone does not issue ordinary restock requests");
            } else {
                assertEquals(1, f.requests.size());
                assertEquals(MaterialRequest.Source.PRINT, f.requests.getFirst().source());
                assertFalse(f.requests.getFirst().acceptsStack(warehouse));
            }

            f.player.inventory.setItem(3, new ItemStack(Items.CRAFTING_TABLE));
            assertTrue(selectTable(f, matching), "An ordinary table must still be selected for building");
            assertEquals(3, f.player.inventory.getSelectedSlot());
            assertEquals(List.of("select:3"), f.traffic);
            assertSame(warehouse, f.player.inventory.getItem(0));
        }
    }

    private static boolean selectTable(MaterialSelectionFixture f, boolean matching) throws Exception {
        return matching
                ? f.matching(stack -> stack.is(Items.CRAFTING_TABLE), new ItemStack(Items.CRAFTING_TABLE), -1)
                : f.items(new Item[]{Items.CRAFTING_TABLE}, -1);
    }

    @Test void defaultInventoryLookupExcludesTheTriggerButCountsPlainTables() throws Exception {
        try (var f = new MaterialSelectionFixture()) {
            f.player.inventory.setItem(9, trigger("虚空仓库"));
            assertFalse(InventoryUtils.playerHasItemInInventory(f.player, Items.CRAFTING_TABLE));
            f.player.inventory.setItem(10, new ItemStack(Items.CRAFTING_TABLE));
            assertTrue(InventoryUtils.playerHasItemInInventory(f.player, Items.CRAFTING_TABLE));
        }
    }

    @Test void explicitInventoryPredicateAllowsManualRetrievalWithoutWeakeningPrinting() throws Exception {
        try (var f = new MaterialSelectionFixture()) {
            var manual = request(MaterialRequest.Source.CHEST_TRACKER_SCREEN, 2);
            var printing = request(MaterialRequest.Source.PRINT, 2);
            f.player.inventory.setItem(9, trigger("虚空仓库"));
            f.player.inventory.setItem(10, trigger("Void Warehouse"));

            assertFalse(InventoryUtils.playerHasItemInInventory(f.player, Items.CRAFTING_TABLE,
                    printing::acceptsStack));
            assertFalse(InventoryUtils.playerHasItemInInventory(f.player, Items.CRAFTING_TABLE,
                    printing::acceptsStack, printing.minimumCount()));
            assertTrue(InventoryUtils.playerHasItemInInventory(f.player, Items.CRAFTING_TABLE,
                    manual::acceptsStack), "The explicit predicate defines a manual retrieval count");
            assertTrue(InventoryUtils.playerHasItemInInventory(f.player, Items.CRAFTING_TABLE,
                    manual::acceptsStack, manual.minimumCount()), "Count matching triggers across slots");
            f.player.inventory.setItem(10, ItemStack.EMPTY);
            assertFalse(InventoryUtils.playerHasItemInInventory(f.player, Items.CRAFTING_TABLE,
                    manual::acceptsStack, manual.minimumCount()), "Keep the requested quantity requirement");
        }
    }
}
//#endif
