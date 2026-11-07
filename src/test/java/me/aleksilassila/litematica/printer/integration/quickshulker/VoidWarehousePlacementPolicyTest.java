//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import me.aleksilassila.litematica.printer.integration.voidwarehouse.VoidWarehousePlacementPolicy;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.action.ClickAction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VoidWarehousePlacementPolicyTest {
    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    @Test void ordinaryBlocksCanUseWarehouseWithoutAnInventoryStack() {
        assertTrue(VoidWarehousePlacementPolicy.supports(new Action(), new Item[]{Items.STONE}, null));
        assertTrue(VoidWarehousePlacementPolicy.supports(new Action(), new Item[]{Items.OAK_STAIRS}, null));
        assertTrue(VoidWarehousePlacementPolicy.supports(new Action(), new Item[]{Items.OAK_SLAB}, null));
    }

    @Test void itemUseAndRepeatedInteractionsStayOnTheInventoryPath() {
        assertFalse(VoidWarehousePlacementPolicy.supports(new ClickAction(), new Item[]{Items.STONE}, null));
        assertFalse(VoidWarehousePlacementPolicy.supports(new Action().setClickRepeatCount(2), new Item[]{Items.STONE}, null));
        assertFalse(VoidWarehousePlacementPolicy.supports(new Action(), new Item[]{Items.WATER_BUCKET}, null));
        assertFalse(VoidWarehousePlacementPolicy.supports(new Action(), new Item[]{Items.POWDER_SNOW_BUCKET}, null));
        assertFalse(VoidWarehousePlacementPolicy.supports(new Action(), new Item[]{Items.BONE_MEAL}, null));
    }

    @Test void slabMergeClicksPlaceThroughTheWarehouseButNoOtherClickActionDoes() {
        // The second half of a double slab merges inside the ordinary placement primitive, so the
        // click is a plain placement on the slab that is already there.
        assertTrue(VoidWarehousePlacementPolicy.supports(new ClickAction(), new Item[]{Items.OAK_SLAB}, null));
        assertTrue(VoidWarehousePlacementPolicy.supports(new ClickAction(), new Item[]{Items.POLISHED_BLACKSTONE_SLAB}, null));
        // A candle is added by the candle block's own use-on hook; item.place() cannot express it.
        assertFalse(VoidWarehousePlacementPolicy.supports(new ClickAction(), new Item[]{Items.CANDLE}, null));
        // Tool clicks were never block placements to begin with.
        assertFalse(VoidWarehousePlacementPolicy.supports(new ClickAction(), new Item[]{Items.IRON_SHOVEL}, null));
        // One slab among other alternatives must not drag the whole requirement through.
        assertFalse(VoidWarehousePlacementPolicy.supports(
                new ClickAction(), new Item[]{Items.OAK_SLAB, Items.CANDLE}, null));
    }

    @Test void componentSensitiveAndSpecialBlockItemsKeepTheirExistingSemantics() {
        assertFalse(VoidWarehousePlacementPolicy.supports(
                new Action().setRequiredStackPredicate(stack -> true), new Item[]{Items.STONE}, null));
        assertFalse(VoidWarehousePlacementPolicy.supports(new Action(), new Item[]{Items.SHULKER_BOX}, null));
        assertFalse(VoidWarehousePlacementPolicy.supports(new Action(), new Item[]{Items.OAK_DOOR}, null));
        assertFalse(VoidWarehousePlacementPolicy.supports(new Action(), new Item[]{Items.RED_BED}, null));
        assertFalse(VoidWarehousePlacementPolicy.supports(new Action(), new Item[]{Items.LILAC}, null));
        assertFalse(VoidWarehousePlacementPolicy.supports(new Action(), new Item[]{Items.OAK_SIGN}, null));
    }

    @Test void supportedBlockActionsCanKeepTheirFinitePrecisionData() {
        assertTrue(VoidWarehousePlacementPolicy.supports(new Action(), new Item[]{Items.CHEST}, new Vec3(18, 64, -3)));
        assertTrue(VoidWarehousePlacementPolicy.supports(new Action(), new Item[]{Items.OAK_STAIRS}, new Vec3(42, 64.99, -3)));
        assertTrue(VoidWarehousePlacementPolicy.supports(new Action(), new Item[]{Items.STONE}, new Vec3(0, 64, -3)));
        assertFalse(VoidWarehousePlacementPolicy.supports(new ClickAction(), new Item[]{Items.STONE}, new Vec3(18, 64, -3)));
        assertFalse(VoidWarehousePlacementPolicy.supports(
                new Action().setClickRepeatCount(2), new Item[]{Items.STONE}, new Vec3(18, 64, -3)));
    }

    @Test void malformedPrecisionCoordinatesNeverQualifyForWarehousePlacement() {
        assertFalse(VoidWarehousePlacementPolicy.supports(new Action(), new Item[]{Items.CHEST}, new Vec3(Double.NaN, 64, -3)));
        assertFalse(VoidWarehousePlacementPolicy.supports(new Action(), new Item[]{Items.CHEST}, new Vec3(18, Double.POSITIVE_INFINITY, -3)));
        assertFalse(VoidWarehousePlacementPolicy.supports(new Action(), new Item[]{Items.CHEST}, new Vec3(18, 64, Double.NEGATIVE_INFINITY)));
    }

    @Test void invalidOrMixedRequirementsCannotSilentlyLoseTheirAlternatives() {
        assertFalse(VoidWarehousePlacementPolicy.supports(new Action(), null, null));
        assertFalse(VoidWarehousePlacementPolicy.supports(new Action(), new Item[0], null));
        assertFalse(VoidWarehousePlacementPolicy.supports(new Action(), new Item[]{Items.AIR}, null));
        assertFalse(VoidWarehousePlacementPolicy.supports(new Action(), new Item[]{Items.STONE, Items.WATER_BUCKET}, null));
        assertFalse(VoidWarehousePlacementPolicy.supports(new Action(), new Item[]{null}, null));
    }
}
//#endif
