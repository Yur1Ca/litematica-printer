//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Native menu updates may be older than our raw USEs; they cannot mint a new send budget. */
class BedrockRawInventoryBudgetTest {
    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    private static Object call(Object owner, String method, Class<?>[] types, Object... args) throws Exception {
        var selected = owner.getClass().getDeclaredMethod(method, types);
        selected.setAccessible(true);
        return selected.invoke(owner, args);
    }

    private static int pistons(Object session) throws Exception {
        return (int) call(session, "availablePistons", new Class<?>[0]);
    }

    private static boolean preparePistons(Object session) throws Exception {
        return (boolean) call(session, "preparePistons", new Class<?>[0]);
    }

    private static int toolUses(Object session) throws Exception {
        return (int) call(session, "availableToolUses", new Class<?>[0]);
    }

    private static void rawUse(Object session, int pistons, int torchSlot, int torches) throws Exception {
        call(session, "recordRawUses", new Class<?>[]{int.class, int.class, int.class}, pistons, torchSlot, torches);
    }

    @Test void anOlderSlotCountCannotRefillTheRawPistonBudgetAndOrdinaryUseDoesNotWait() throws Exception {
        try (var f = new BedrockInventorySessionTest.Fixture()) {
            f.player().getInventory().setItem(40, new ItemStack(Items.PISTON, 4));
            f.player().getInventory().setItem(6, new ItemStack(Items.REDSTONE_TORCH, 3));
            Object session = f.session();
            f.validateInitial(session);
            long markers = f.markers();

            rawUse(session, 1, 6, 1);

            assertEquals(3, pistons(session));
            assertEquals(3, f.player().getInventory().getItem(40).getCount());
            assertEquals(2, f.player().getInventory().getItem(6).getCount());
            assertFalse((boolean) call(session, "isSettling", new Class<?>[0]));
            assertEquals(markers, f.markers(), "A raw USE does not create a full-inventory round trip");
            // Apply a real vanilla full-menu update whose counts precede that raw USE.
            f.apply(f.contents(new ItemStack(Items.PISTON, 4), new ItemStack(Items.REDSTONE_TORCH, 3)), 48);
            assertEquals(4, f.player().getInventory().getItem(40).getCount());
            assertEquals(3, pistons(session), "An older slot update cannot create another raw placement allowance");

            rawUse(session, 3, -1, 0);
            assertEquals(0, pistons(session));
            assertFalse(preparePistons(session), "A spent budget requires calibration, even if a stale slot still has an item");
            assertEquals(markers + 1, f.markers());
            assertEquals(0, f.actions.offhandSwaps);
            assertFalse(preparePistons(session));
            assertEquals(markers + 1, f.markers(), "An outstanding calibration has one owner");
            f.acknowledge();
            assertTrue(preparePistons(session));
            assertEquals(1, pistons(session), "The completed snapshot, not a raw callback, rebuilds the budget");
        }
    }

    @Test void dirtyRawUsesAreCalibratedBeforeTheNextRelativeSwapAndTheSwapKeepsItsOwnFence() throws Exception {
        try (var f = new BedrockInventorySessionTest.Fixture()) {
            f.player().getInventory().setItem(40, new ItemStack(Items.PISTON, 4));
            f.player().getInventory().setItem(11, new ItemStack(Items.REDSTONE_TORCH, 8));
            Object session = f.session();
            f.validateInitial(session);
            rawUse(session, 1, -1, 0);

            assertFalse((boolean) call(session, "prepareMaterial", new Class<?>[]{net.minecraft.world.item.Item.class},
                    Items.REDSTONE_TORCH));
            assertEquals(0, f.actions.materialMoves, "Unknown raw USE outcomes must settle before a relative menu click");
            assertEquals(2, f.markers());
            f.acknowledge();
            assertFalse((boolean) call(session, "prepareMaterial", new Class<?>[]{net.minecraft.world.item.Item.class},
                    Items.REDSTONE_TORCH));
            assertEquals(1, f.actions.materialMoves);
            assertEquals(3, f.markers(), "The actual relative SWAP still waits for its own response");
            assertTrue((boolean) call(session, "isSettling", new Class<?>[0]));
            assertFalse((boolean) call(session, "prepareMaterial", new Class<?>[]{net.minecraft.world.item.Item.class},
                    Items.REDSTONE_TORCH));
            assertEquals(1, f.actions.materialMoves);
            f.acknowledge();
            assertTrue((boolean) call(session, "prepareMaterial", new Class<?>[]{net.minecraft.world.item.Item.class},
                    Items.REDSTONE_TORCH));
        }
    }

    @Test void anOlderDamageUpdateCannotRestoreSpentUsesAndAnExhaustedToolBudgetCalibrates() throws Exception {
        try (var f = new BedrockInventorySessionTest.Fixture()) {
            var tool = new ItemStack(Items.DIAMOND_PICKAXE);
            tool.setDamageValue(tool.getMaxDamage() - 2);
            f.player().getInventory().setItem(0, tool);
            Object session = f.session();
            f.validateInitial(session);
            assertTrue((boolean) call(session, "prepareTool", new Class<?>[]{net.minecraft.world.level.block.state.BlockState.class, boolean.class},
                    Blocks.PISTON.defaultBlockState(), true));
            assertEquals(2, toolUses(session));

            call(session, "recordToolUse", new Class<?>[0]);
            f.apply(f.contents(f.player().getInventory().getItem(40).copy(),
                    f.player().getInventory().getItem(6).copy()), 48); // A real older menu update still has pre-break damage.
            assertEquals(1, toolUses(session), "A stale damage update cannot authorize a second copy of the same use");
            call(session, "recordToolUse", new Class<?>[0]);
            assertEquals(0, toolUses(session));
            assertFalse((boolean) call(session, "prepareTool", new Class<?>[]{net.minecraft.world.level.block.state.BlockState.class, boolean.class},
                    Blocks.PISTON.defaultBlockState(), true));
            assertEquals(2, f.markers(), "Budget exhaustion calibrates instead of guessing that the tool survived");
            f.acknowledge();
            assertTrue((boolean) call(session, "prepareTool", new Class<?>[]{net.minecraft.world.level.block.state.BlockState.class, boolean.class},
                    Blocks.PISTON.defaultBlockState(), true));
            assertEquals(2, toolUses(session));
        }
    }

    @Test void aRejectedLastPistonUseCalibratesEvenWhenTheClientPredictsNoMaterialAnywhere() throws Exception {
        try (var f = new BedrockInventorySessionTest.Fixture()) {
            f.player().getInventory().setItem(40, new ItemStack(Items.PISTON));
            Object session = f.session();
            f.validateInitial(session);
            rawUse(session, 1, -1, 0);
            assertEquals(0, f.player().getInventory().countItem(Items.PISTON));

            assertFalse(preparePistons(session));

            assertEquals(2, f.markers(), "A failed raw USE can leave one native piston without producing any changed-slot packet");
            assertTrue((boolean) call(session, "isSettling", new Class<?>[0]));
            f.apply(f.contents(new ItemStack(Items.PISTON), ItemStack.EMPTY), 48);
            f.acknowledge();
            assertTrue(preparePistons(session));
            assertEquals(1, pistons(session));
        }
    }

    @Test void exhaustedMaterialCanBeReconciledBeforeRequestingAProviderWithoutPerUsePolling() throws Exception {
        try (var f = new BedrockInventorySessionTest.Fixture()) {
            f.player().getInventory().setItem(6, new ItemStack(Items.REDSTONE_TORCH));
            Object session = f.session();
            f.validateInitial(session);
            assertFalse((boolean) call(session, "reconcileInventoryIfDirty", new Class<?>[0]));
            rawUse(session, 0, 6, 1);
            assertEquals(0, f.player().getInventory().countItem(Items.REDSTONE_TORCH));
            assertEquals(1, f.markers(), "A normal raw send does not poll inventory");

            assertTrue((boolean) call(session, "reconcileInventoryIfDirty", new Class<?>[0]));
            assertTrue((boolean) call(session, "reconcileInventoryIfDirty", new Class<?>[0]));
            assertEquals(2, f.markers(), "All shortage observations share the same pending calibration");
            f.apply(f.contents(f.player().getInventory().getItem(40).copy(), new ItemStack(Items.REDSTONE_TORCH)), 49);
            f.acknowledge();
            assertFalse((boolean) call(session, "reconcileInventoryIfDirty", new Class<?>[0]));
            assertFalse((boolean) call(session, "reconcileInventoryIfDirty", new Class<?>[0]));
            assertEquals(2, f.markers(), "A completed reconciliation is not repeated without another dirty send");
            assertEquals(1, f.player().getInventory().countItem(Items.REDSTONE_TORCH));
        }
    }

    @Test void rawConsumptionAbsorbsALowerVisibleCountBeforeChargingItsOwnUse() throws Exception {
        try (var f = new BedrockInventorySessionTest.Fixture()) {
            f.player().getInventory().setItem(40, new ItemStack(Items.PISTON, 4));
            Object session = f.session();
            f.validateInitial(session);
            f.player().getInventory().getItem(40).setCount(2);

            rawUse(session, 1, -1, 0);

            f.apply(f.contents(new ItemStack(Items.PISTON, 4), ItemStack.EMPTY), 48);
            assertEquals(1, pistons(session), "An earlier lower observation plus our own USE remains a conservative ceiling");
        }
    }

    @Test void predictedConsumptionDoesNotChargeVanillasAlreadyReducedCountTwice() throws Exception {
        try (var f = new BedrockInventorySessionTest.Fixture()) {
            f.player().getInventory().setItem(6, new ItemStack(Items.REDSTONE_TORCH, 4));
            Object session = f.session();
            f.validateInitial(session);
            assertTrue((boolean) call(session, "prepareMaterial", new Class<?>[]{net.minecraft.world.item.Item.class}, Items.REDSTONE_TORCH));
            f.player().getInventory().getItem(6).shrink(1); // The existing vanilla useItemOn prediction has already consumed it.

            call(session, "recordPredictedUse", new Class<?>[0]);

            assertEquals(3, call(session, "availableCount", new Class<?>[]{int.class}, 6));
            f.apply(f.contents(f.player().getInventory().getItem(40).copy(), new ItemStack(Items.REDSTONE_TORCH, 4)), 48);
            assertEquals(3, call(session, "availableCount", new Class<?>[]{int.class}, 6));
        }
    }

    @Test void aToolUseAbsorbsLowerVisibleDurabilityBeforeChargingItsOwnDamage() throws Exception {
        try (var f = new BedrockInventorySessionTest.Fixture()) {
            var tool = new ItemStack(Items.DIAMOND_PICKAXE);
            tool.setDamageValue(tool.getMaxDamage() - 4);
            f.player().getInventory().setItem(0, tool);
            Object session = f.session();
            f.validateInitial(session);
            f.player().getInventory().getItem(0).setDamageValue(tool.getMaxDamage() - 2);

            call(session, "recordToolUse", new Class<?>[0]);

            f.player().getInventory().getItem(0).setDamageValue(tool.getMaxDamage() - 4);
            assertEquals(1, toolUses(session), "Later damage observations cannot undo the lower durability plus our START debit");
        }
    }
}
//#endif
