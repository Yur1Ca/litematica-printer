package me.aleksilassila.litematica.printer.integration.containersync;

import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;
import static me.aleksilassila.litematica.printer.integration.containersync.ContainerSyncControllerTest.*;
import static org.junit.jupiter.api.Assertions.*;

class ContainerSyncTypesTest {
    @BeforeAll static void bootstrap() { ContainerSyncPlanTest.bootstrap(); }

    static Stream<Arguments> containers() {
        return Stream.of(
                Arguments.of(Blocks.CHEST, 27, MenuType.GENERIC_9x3),
                Arguments.of(Blocks.CHEST, 54, MenuType.GENERIC_9x6),
                Arguments.of(Blocks.BARREL, 27, MenuType.GENERIC_9x3),
                Arguments.of(Blocks.SHULKER_BOX, 27, MenuType.SHULKER_BOX),
                Arguments.of(Blocks.HOPPER, 5, MenuType.HOPPER),
                Arguments.of(Blocks.DISPENSER, 9, MenuType.GENERIC_3x3),
                Arguments.of(Blocks.DROPPER, 9, MenuType.GENERIC_3x3)
                //#if MC >= 12004
                , Arguments.of(Blocks.CRAFTER, 9, MenuType.CRAFTER_3x3)
                //#endif
        );
    }

    @ParameterizedTest @MethodSource("containers")
    void acceptsOnlyTheSelectedContainersActualMenu(Block block, int size, MenuType<?> type) throws Exception {
        try (var f = new Fixture()) {
            set(f.controller, ContainerSyncController.class, "block", block);
            set(f.controller, ContainerSyncController.class, "size", size);
            f.phase("OPENING");
            f.controller.opened(8, type);
            assertEquals("CONTENT", field(f.controller, "phase").toString());
            assertEquals(8, field(f.controller, "candidate"));
            assertFalse(f.otherInventoryOwner());
        }
    }

    @ParameterizedTest @MethodSource("containers")
    void aDifferentMenuCannotBeClaimedByTheBatch(Block block, int size, MenuType<?> type) throws Exception {
        try (var f = new Fixture()) {
            set(f.controller, ContainerSyncController.class, "block", block);
            set(f.controller, ContainerSyncController.class, "size", size);
            f.phase("OPENING");
            f.controller.opened(8, type == MenuType.HOPPER ? MenuType.GENERIC_3x3 : MenuType.HOPPER);
            assertEquals("PAUSED", field(f.controller, "phase").toString());
            assertTrue(f.otherInventoryOwner());
        }
    }

    @ParameterizedTest @MethodSource("containers")
    void actualVanillaMenuPreservesStorageAndAll36PlayerSlotIndices(Block block, int size, MenuType<?> type) throws Exception {
        try (var f = new Fixture()) {
            var inventory = inventory(f.client.player);
            var menu = type.create(7, inventory);
            var layout = ContainerSyncLayout.of(block, size);
            assertTrue(layout.acceptsSlots(menu, inventory));
            var state = block.defaultBlockState();
            if (size == 54) state = state.setValue(net.minecraft.world.level.block.ChestBlock.TYPE,
                    net.minecraft.world.level.block.state.properties.ChestType.LEFT);
            assertSame(layout, ContainerSyncLayout.of(state));
            var input = menu.slots.stream().map(s -> s.getItem()).toList();
            var editable = layout.contents(input);
            assertNotNull(editable);
            assertEquals(size + 36, editable.size());
            var seen = new java.util.HashSet<Integer>();
            for (int i = size; i < editable.size(); i++) seen.add(menu.getSlot(i).getContainerSlot());
            assertEquals(java.util.stream.IntStream.range(0, 36).boxed().collect(java.util.stream.Collectors.toSet()), seen);
            assertNull(layout.contents(input.subList(0, input.size() - 1)));
            // Replacing a player slot with a storage slot must fail closed even when sizes match.
            menu.slots.set(size, menu.getSlot(0));
            assertFalse(layout.acceptsSlots(menu, inventory));
        }
    }

    @org.junit.jupiter.api.Test void hopperFilterCountsAreExactAndSurplusReturnsToInventory() {
        var wanted = java.util.List.of(new ItemStack(Items.IRON_INGOT, 41), new ItemStack(Items.STICK),
                new ItemStack(Items.STICK), new ItemStack(Items.STICK), new ItemStack(Items.STICK));
        var initial = new java.util.ArrayList<>(java.util.Collections.nCopies(41, ItemStack.EMPTY));
        initial.set(0, new ItemStack(Items.IRON_INGOT, 64));
        initial.set(5, new ItemStack(Items.STICK, 64));
        var plan = ContainerSyncPlan.create(wanted, ContainerSyncLayout.HOPPER.contents(initial), ContainerSyncPlanTest.RULES);
        ContainerSyncPickupSemantics.verify(plan, ContainerSyncPlanTest.RULES);
        var end = plan.steps().get(plan.steps().size() - 1);
        assertTrue(ContainerSyncPlan.matches(wanted, end.items().subList(0, 5)));
        assertEquals(23, end.items().subList(5, 41).stream().filter(s -> s.is(Items.IRON_INGOT)).mapToInt(ItemStack::getCount).sum());
    }

    @org.junit.jupiter.api.Test void dispenserBoatPrefillHonoursNonStackableSlots() {
        var wanted = java.util.Collections.nCopies(9, new ItemStack(Items.OAK_BOAT));
        var initial = new java.util.ArrayList<>(java.util.Collections.nCopies(45, ItemStack.EMPTY));
        for (int i = 9; i < 18; i++) initial.set(i, new ItemStack(Items.OAK_BOAT));
        var plan = ContainerSyncPlan.create(wanted, ContainerSyncLayout.DISPENSER.contents(initial), ContainerSyncPlanTest.RULES);
        ContainerSyncPickupSemantics.verify(plan, ContainerSyncPlanTest.RULES);
        assertTrue(ContainerSyncPlan.matches(wanted, plan.steps().get(plan.steps().size() - 1).items().subList(0, 9)));
        initial.set(17, ItemStack.EMPTY);
        var missing = ContainerSyncPlan.create(wanted, initial, ContainerSyncPlanTest.RULES);
        assertEquals(ContainerSyncPlan.Problem.MISSING_ITEMS, missing.problem());
        assertTrue(missing.steps().isEmpty());
    }

    // Inventory gained an equipment constructor parameter; create the real inventory in each version.
    static Inventory inventory(net.minecraft.world.entity.player.Player player) throws Exception {
        var constructor = Inventory.class.getConstructors()[0];
        var args = new Object[constructor.getParameterCount()];
        args[0] = player;
        for (int i = 1; i < args.length; i++) args[i] = constructor.getParameterTypes()[i].getDeclaredConstructor().newInstance();
        return (Inventory) constructor.newInstance(args);
    }

    static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
