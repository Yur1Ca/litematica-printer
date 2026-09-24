package me.aleksilassila.litematica.printer.integration.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChestTrackerRestorePhaseTest {
    @Test
    void inventoryMaterialCannotCompleteAnOutstandingContainerRestore() {
        assertTrue(ChestTrackerAdapter.waitForRestoreContent(ChestTrackerAdapter.Phase.RESTORE_WAIT_CONTENT));
        assertFalse(ChestTrackerAdapter.waitForRestoreContent(ChestTrackerAdapter.Phase.WAITING_INVENTORY));
    }
}
