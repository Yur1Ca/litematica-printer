package me.aleksilassila.litematica.printer.integration.inventory;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NestedShulkerReturnTest {
    @Test
    void restoreRequiresOriginalEmptySlotAndTrackedInventorySlot() {
        NestedShulkerReturn restore = new NestedShulkerReturn();
        restore.recordTransfer(new BlockPos(1, 2, 3), 4, ItemStack.EMPTY, 8);

        assertTrue(restore.beginRestore(false, true));
        assertTrue(restore.acceptsSourceSlot(9, ItemStack.EMPTY));
        assertFalse(restore.acceptsSourceSlot(4, ItemStack.EMPTY));
        assertFalse(restore.acceptsInventorySlot(8, ItemStack.EMPTY));
        assertFalse(restore.acceptsInventorySlot(36, ItemStack.EMPTY));
    }

    @Test
    void restoreSyncAndResetCannotLeakIntoNextRequest() {
        NestedShulkerReturn restore = new NestedShulkerReturn();
        restore.recordTransfer(new BlockPos(1, 2, 3), 4, ItemStack.EMPTY, 8);
        assertFalse(restore.beginRestore(true, true));
        assertFalse(restore.beginRestore(false, false));
        assertTrue(restore.beginRestore(false, true));
        restore.markWaitingForSync(10L);
        assertTrue(restore.waitingForSync(14L));
        assertFalse(restore.waitingForSync(15L));

        restore.clear();
        assertFalse(restore.hasSource());
        assertFalse(restore.isRestoring());
    }
}
