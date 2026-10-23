//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static me.aleksilassila.litematica.printer.integration.quickshulker.CancelledSwapLifecycleTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Characterizes the public material entry points before narrowing their execution dependencies. */
class MaterialSelectorBaselineTest {
    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    @Test void heldLaterCandidateWinsWithoutSelectingTheEarlierCandidate() throws Exception {
        try (var f = new MaterialSelectionFixture()) {
            f.player.inventory.setItem(0, new ItemStack(Items.DIRT, 8));
            f.player.inventory.setItem(1, new ItemStack(Items.STONE, 8));
            assertTrue(f.items(new Item[]{Items.STONE, Items.DIRT}, -1));
            assertEquals(0, f.player.inventory.getSelectedSlot());
            assertTrue(f.traffic.isEmpty());
            assertTrue(f.requests.isEmpty());
        }
    }

    @Test void candidateOrderPrecedesSlotOrderAndFirstMatchingSlotWins() throws Exception {
        try (var f = new MaterialSelectionFixture()) {
            f.player.inventory.setItem(1, new ItemStack(Items.DIRT, 64));
            f.player.inventory.setItem(7, new ItemStack(Items.STONE, 3));
            f.player.inventory.setItem(8, new ItemStack(Items.STONE, 64));
            assertTrue(f.items(new Item[]{Items.STONE, Items.DIRT}, -1));
            assertEquals(List.of("select:7"), f.traffic);
            assertEquals(7, f.player.inventory.getSelectedSlot());
            assertEquals(3, f.player.getMainHandItem().getCount());
            assertFalse(f.guard.isWaiting(), "A hotbar selection does not acquire the backpack confirmation barrier");
        }
    }

    @Test void backpackSwapWaitsAcrossTheTickBoundaryEvenWhenTheStackArrivesImmediately() throws Exception {
        try (var f = new MaterialSelectionFixture()) {
            f.player.inventory.setItem(10, new ItemStack(Items.STONE, 3));
            f.player.inventory.setItem(11, new ItemStack(Items.STONE, 64));
            assertFalse(f.items(new Item[]{Items.STONE}, -1));
            // The existing inventory executor re-finds matching stacks from the end of the menu.
            assertEquals(List.of("select:2", "swap:11:2"), f.traffic);
            assertTrue(f.guard.isWaiting());
            f.player.inventory.setItem(2, new ItemStack(Items.STONE, 64));
            assertFalse(f.items(new Item[]{Items.STONE}, -1));
            assertFalse(f.matching(stack -> stack.is(Items.STONE), new ItemStack(Items.STONE), -1));
            assertTrue(f.requests.isEmpty());
            assertEquals(List.of("select:2", "swap:11:2"), f.traffic);
            f.tick++;
            assertTrue(f.items(new Item[]{Items.STONE}, -1));
            assertEquals(List.of("select:2", "swap:11:2"), f.traffic);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void failedFirstInventoryWriteDoesNotTryAnotherStackOrRequestMore(boolean matching) throws Exception {
        try (var f = new MaterialSelectionFixture()) {
            f.player.inventory.setItem(10, new ItemStack(Items.STONE, 3));
            f.player.inventory.setItem(11, new ItemStack(Items.STONE, 64));
            f.client.gameMode = null;
            assertFalse(matching ? f.matching(stack -> stack.is(Items.STONE), new ItemStack(Items.STONE), -1)
                    : f.items(new Item[]{Items.STONE}, -1));
            assertEquals(List.of("select:2"), f.traffic);
            assertTrue(f.requests.isEmpty());
            assertFalse(f.guard.isWaiting());
        }
    }

    @Test void reserveCountsAllMainInventoryStacksButExcludesOffhandAndAllowsOnlySurplus() throws Exception {
        try (var f = new MaterialSelectionFixture()) {
            var held = new ItemStack(Items.STONE, 1);
            f.player.inventory.setItem(0, held);
            f.player.inventory.setItem(10, new ItemStack(Items.STONE, 3));
            f.player.inventory.setItem(40, new ItemStack(Items.STONE, 64));
            assertEquals(0, f.surplus(held, null, 4));
            assertFalse(f.items(new Item[]{Items.STONE}, 4));
            assertEquals(1, f.requests.size());
            f.player.inventory.setItem(35, new ItemStack(Items.STONE, 1));
            assertEquals(1, f.surplus(held, null, 4));
            assertTrue(f.items(new Item[]{Items.STONE}, 4));
            assertTrue(f.traffic.isEmpty());
        }
    }

    @Test void reserveDoesNotRestrictUnlimitedEmptyDamageableOrCreativeStacks() throws Exception {
        try (var f = new MaterialSelectionFixture()) {
            assertEquals(0, f.surplus(null, null, 0));
            assertEquals(Integer.MAX_VALUE, f.surplus(new ItemStack(Items.STONE), null, -1));
            assertEquals(Integer.MAX_VALUE, f.surplus(ItemStack.EMPTY, null, 999));
            assertEquals(Integer.MAX_VALUE, f.surplus(new ItemStack(Items.DIAMOND_PICKAXE), null, 999));
            f.player.getAbilities().instabuild = true;
            assertEquals(Integer.MAX_VALUE, f.surplus(new ItemStack(Items.STONE), null, 999));
            assertTrue(f.blocked(new Item[]{Items.STONE}, null, 999).isEmpty());
        }
    }

    @Test void reserveDiagnosticUsesCandidateThenSlotOrderAndReturnsACopy() throws Exception {
        try (var f = new MaterialSelectionFixture()) {
            f.player.inventory.setItem(1, new ItemStack(Items.DIRT, 1));
            var first = new ItemStack(Items.STONE, 2);
            f.player.inventory.setItem(10, first);
            f.player.inventory.setItem(11, new ItemStack(Items.STONE, 3));
            ItemStack blocked = f.blocked(new Item[]{Items.STONE, Items.DIRT}, null, 5);
            assertTrue(blocked.is(Items.STONE));
            assertEquals(2, blocked.getCount());
            assertNotSame(first, blocked);
            blocked.shrink(1);
            assertEquals(2, first.getCount());
            assertTrue(f.blocked(new Item[]{Items.STONE}, null, -1).isEmpty());
            assertTrue(f.blocked(new Item[]{Items.STONE}, null, 4).isEmpty());
        }
    }

    @Test void predicateReserveCountsOnlyEligibleStacksAndIgnoresCandidateItemOrder() throws Exception {
        try (var f = new MaterialSelectionFixture()) {
            f.player.inventory.setItem(0, filledBox());
            var empty = new ItemStack(Items.SHULKER_BOX);
            f.player.inventory.setItem(10, empty);
            f.player.inventory.setItem(40, new ItemStack(Items.SHULKER_BOX));
            assertEquals(0, f.surplus(empty, MaterialRequest::isEmptyShulker, 1));
            assertTrue(MaterialRequest.isEmptyShulker(f.blocked(new Item[]{Items.DIRT}, MaterialRequest::isEmptyShulker, 1)));
            assertFalse(f.matching(MaterialRequest::isEmptyShulker, new ItemStack(Items.SHULKER_BOX), 1));
            assertTrue(f.traffic.isEmpty());
            assertTrue(f.requests.getFirst().emptyShulkerOnly());
        }
    }

    @Test void predicateSkipsFilledHandAndEarlierFilledBoxThenWaitsForTheEmptyBoxSwap() throws Exception {
        try (var f = new MaterialSelectionFixture()) {
            f.player.inventory.setItem(0, filledBox());
            f.player.inventory.setItem(1, filledBox());
            f.player.inventory.setItem(10, new ItemStack(Items.SHULKER_BOX));
            assertFalse(f.matching(MaterialRequest::isEmptyShulker, new ItemStack(Items.SHULKER_BOX), -1));
            assertEquals(List.of("select:2", "swap:10:2"), f.traffic);
            assertTrue(f.guard.isWaiting());
            assertTrue(f.requests.isEmpty());
        }
    }

    @Test void creativeItemsChooseTheFirstCandidateBeforeScanningTheInventory() throws Exception {
        try (var f = new MaterialSelectionFixture()) {
            f.player.getAbilities().instabuild = true;
            f.player.inventory.setItem(7, new ItemStack(Items.DIRT, 64));
            assertTrue(f.items(new Item[]{Items.STONE, Items.DIRT}, 999));
            assertEquals(List.of("select:2", "creative:38"), f.traffic);
            assertTrue(f.player.getMainHandItem().is(Items.STONE));
            assertEquals(1, f.player.getMainHandItem().getCount());
            assertFalse(f.guard.isWaiting());
        }
    }

    @Test void creativePredicatePrefersExistingEligibleInventoryOverFallback() throws Exception {
        try (var f = new MaterialSelectionFixture()) {
            f.player.getAbilities().instabuild = true;
            f.player.inventory.setItem(7, new ItemStack(Items.DIRT, 4));
            assertTrue(f.matching(stack -> stack.is(Items.STONE) || stack.is(Items.DIRT), new ItemStack(Items.STONE), 999));
            assertEquals(List.of("select:7"), f.traffic);
            assertTrue(f.player.getMainHandItem().is(Items.DIRT));
        }
    }

    @Test void creativePredicateFallbackCopiesTheRequestedStack() throws Exception {
        try (var f = new MaterialSelectionFixture()) {
            f.player.getAbilities().instabuild = true;
            var fallback = new ItemStack(Items.STONE, 3);
            assertTrue(f.matching(stack -> stack.is(Items.STONE), fallback, 999));
            assertEquals(List.of("select:2", "creative:38"), f.traffic);
            assertEquals(3, f.player.getMainHandItem().getCount());
            assertNotSame(fallback, f.player.getMainHandItem());
            f.player.getMainHandItem().shrink(1);
            assertEquals(3, fallback.getCount());
            assertTrue(f.requests.isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void invalidPredicateFallbackCannotCreateOrRequestMaterial(boolean creative) throws Exception {
        try (var f = new MaterialSelectionFixture()) {
            f.player.getAbilities().instabuild = creative;
            assertFalse(f.matching(null, new ItemStack(Items.SHULKER_BOX), -1));
            assertFalse(f.matching(stack -> false, new ItemStack(Items.SHULKER_BOX), -1));
            assertFalse(f.matching(MaterialRequest::isEmptyShulker, filledBox(), -1));
            assertFalse(f.matching(MaterialRequest::isEmptyShulker, null, -1));
            assertTrue(f.traffic.isEmpty());
            assertTrue(f.requests.isEmpty());
        }
    }

    @ParameterizedTest @EnumSource(value = MaterialRequest.Source.class, names = {"PRINT", "MINE", "PICK_BLOCK"})
    void missingItemsPreserveCandidateOrderAndOwnerAtTheRealCoordinator(MaterialRequest.Source source) throws Exception {
        try (var f = new MaterialSelectionFixture()) {
            assertFalse(f.items(new Item[]{Items.DIRT, Items.STONE}, -1, source));
            assertEquals(1, f.requests.size());
            MaterialRequest request = f.requests.getFirst();
            assertEquals(List.of(Items.DIRT, Items.STONE), request.acceptedItems());
            assertEquals(Items.DIRT, request.preferredItem());
            assertEquals(source, request.source());
            assertFalse(request.emptyShulkerOnly());
            assertTrue(f.traffic.isEmpty());
        }
    }

    @Test void defaultSourceAndEmptyBoxRequestRemainPrintOwned() throws Exception {
        try (var f = new MaterialSelectionFixture()) {
            assertFalse(f.items(new Item[]{Items.STONE}, -1));
            assertEquals(MaterialRequest.Source.PRINT, f.requests.getFirst().source());
            var emptyRequests = new ArrayList<MaterialRequest>();
            f.installRequests(emptyRequests);
            assertFalse(f.matching(MaterialRequest::isEmptyShulker, new ItemStack(Items.SHULKER_BOX), -1));
            assertEquals(1, emptyRequests.size());
            assertEquals(MaterialRequest.Source.PRINT, emptyRequests.getFirst().source());
            assertTrue(emptyRequests.getFirst().emptyShulkerOnly());
            assertFalse(emptyRequests.getFirst().acceptsStack(filledBox()));
        }
    }

    @Test void nullPlayerAndEmptyCandidatesRetainTheirExistingShortCircuitBehavior() throws Exception {
        try (var f = new MaterialSelectionFixture()) {
            assertEquals(false, f.invoke("switchToItems", new Class<?>[]{LocalPlayer.class, Item[].class, int.class},
                    null, new Item[]{Items.STONE}, -1));
            assertEquals(false, f.invoke("switchToMatchingStack", new Class<?>[]{LocalPlayer.class, Predicate.class,
                    ItemStack.class, int.class}, null, (Predicate<ItemStack>) stack -> true, new ItemStack(Items.STONE), -1));
            assertTrue(f.items(null, -1));
            assertTrue(f.items(new Item[0], -1));
            assertTrue(f.blocked(null, null, 0).isEmpty());
            assertTrue(f.traffic.isEmpty());
            assertTrue(f.requests.isEmpty());
        }
    }

    @Test void laterDecisionReadsTheCurrentGuardAndProviderRatherThanKeepingTheEarlierRuntimeServices() throws Exception {
        try (var f = new MaterialSelectionFixture()) {
            f.player.inventory.setItem(0, new ItemStack(Items.STONE));
            assertTrue(f.items(new Item[]{Items.STONE}, -1));
            var replacement = new InventorySwitchGuard(f.client, () -> f.tick);
            replacement.markSwitchIfNeeded(Items.STONE);
            set(f.runtime, PrinterRuntime.class, "inventorySwitchGuard", replacement);
            assertFalse(f.items(new Item[]{Items.STONE}, -1));
            assertFalse(f.matching(stack -> stack.is(Items.STONE), new ItemStack(Items.STONE), -1));
            var currentRequests = new ArrayList<MaterialRequest>();
            f.installRequests(currentRequests);
            f.tick++;
            assertFalse(f.items(new Item[]{Items.DIRT}, -1));
            assertTrue(f.requests.isEmpty());
            assertEquals(List.of(Items.DIRT), currentRequests.getFirst().acceptedItems());
        }
    }

    private static ItemStack filledBox() {
        var stack = new ItemStack(Items.SHULKER_BOX);
        stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, 64))));
        return stack;
    }

    @Test void reserveBlockedSelectionCountsTheMainInventoryOnlyOncePerDecision() throws Exception {
        try (var f = new MaterialSelectionFixture()) {
            for (int slot = 0; slot < 36; slot++) f.player.inventory.setItem(slot, new ItemStack(Items.STONE));
            int[] evaluations = {0};
            Predicate<ItemStack> predicate = stack -> { evaluations[0]++; return stack.is(Items.STONE); };
            assertFalse(f.matching(predicate, new ItemStack(Items.STONE), 36));
            assertTrue(evaluations[0] <= 74, "Selection should scan matches and count reserves, not recount for every stack: " + evaluations[0]);
            assertTrue(f.traffic.isEmpty());
            assertTrue(f.requests.isEmpty());

            // A later decision must observe newly arrived stock, even within the same client tick.
            f.player.inventory.getItem(35).grow(1);
            assertTrue(f.matching(predicate, new ItemStack(Items.STONE), 36));
            f.player.inventory.getItem(35).shrink(1);
            assertFalse(f.matching(predicate, new ItemStack(Items.STONE), 36));
            assertTrue(f.traffic.isEmpty());
        }
    }

    @Test void predicateReserveDoesNotHideALaterDamageableStack() throws Exception {
        try (var f = new MaterialSelectionFixture()) {
            f.player.inventory.setItem(0, new ItemStack(Items.STONE));
            f.player.inventory.setItem(7, new ItemStack(Items.DIAMOND_PICKAXE));
            Predicate<ItemStack> predicate = stack -> stack.is(Items.STONE) || stack.is(Items.DIAMOND_PICKAXE);
            assertTrue(f.matching(predicate, new ItemStack(Items.STONE), 999));
            assertEquals(List.of("select:7"), f.traffic);
            assertTrue(f.player.getMainHandItem().is(Items.DIAMOND_PICKAXE));
        }
    }
}
//#endif
