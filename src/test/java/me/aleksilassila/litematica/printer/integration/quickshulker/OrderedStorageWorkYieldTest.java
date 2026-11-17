//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.util.List;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real maintenance and packet acknowledgements, with a manually advanced game clock. */
class OrderedStorageWorkYieldTest {
    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    @Test void completedReturnLeavesAWorkTickBeforeStartingTheNextPressureReturn() throws Exception {
        try (var f = new Fixture()) {
            f.storage.restorePendingItem();
            f.adapter.tick();
            f.reply(f.menu, 1, List.of(ItemStack.EMPTY, ItemStack.EMPTY), new ItemStack(Items.PISTON, 64));
            f.reply(f.menu, 2, List.of(new ItemStack(Items.PISTON, 64), ItemStack.EMPTY), ItemStack.EMPTY);
            assertTrue(f.player.closed);
            assertEquals(1, f.tracked().size());
            int sent = f.connection.packets.size();

            f.level.tick = 101;
            f.adapter.tick();
            assertEquals(sent, f.connection.packets.size(), "The next return must leave room for work");
            assertFalse(f.storage.hasPendingRestore());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertFalse(f.storage.tryRestoreForInventoryPressure(), "The material request entry must also yield");
            assertTrue(f.session().isPressureRecoveryActive(), "Yielding must preserve the space recovery target");

            f.level.tick = 102;
            f.adapter.tick();
            assertTrue(f.storage.isWaitingForRestoreContainer(), "Pressure maintenance must resume afterwards");
            assertEquals(sent + 1, f.connection.packets.size());
            assertTrue(f.broker.isResourceHeld(ResourceLease.INVENTORY));
        }
    }

    @Test void newlyDeliveredMaterialGetsAWorkTickWithoutLosingExistingReturnRecords() throws Exception {
        try (var f = new Fixture()) {
            f.session().clearPending();
            f.player.containerMenu = f.player.inventoryMenu;
            var box = f.player.inventory.getItem(10);
            f.storage.newItem(new ItemStack(Items.PISTON, 32), box, 0, 10, 11);
            int tracked = f.tracked().size();

            f.level.tick = 101;
            f.adapter.tick();
            assertTrue(f.connection.packets.isEmpty(), "Maintenance cannot preempt freshly delivered material");
            assertFalse(f.storage.hasPendingRestore());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertFalse(f.storage.tryRestoreForInventoryPressure());
            assertEquals(tracked, f.tracked().size());

            f.level.tick = 102;
            f.adapter.tick();
            assertTrue(f.storage.isWaitingForRestoreContainer());
            assertEquals(1, f.connection.packets.size());
        }
    }

    @Test void consecutiveDeliveriesCannotKeepExtendingThePressureMaintenanceWindow() throws Exception {
        try (var f = new Fixture()) {
            f.session().clearPending();
            f.player.containerMenu = f.player.inventoryMenu;
            var box = f.player.inventory.getItem(10).copy();
            f.storage.newItem(new ItemStack(Items.PISTON, 32), box, 0, 10, 11);
            f.level.tick = 101;
            f.storage.newItem(new ItemStack(Items.PISTON, 32), box, 0, 10, 11);
            f.adapter.tick();
            assertTrue(f.connection.packets.isEmpty());

            f.level.tick = 102;
            f.storage.newItem(new ItemStack(Items.PISTON, 32), box, 0, 10, 11);
            f.adapter.tick();
            assertTrue(f.storage.isWaitingForRestoreContainer(), "Repeated deliveries cannot starve an older eligible return");
            assertEquals(1, f.connection.packets.size());
        }
    }

    private static final class Fixture extends BedrockOrderedStorageBoundaryTest.Fixture {
        Fixture() throws Exception {
            var box = QuickShulkerQueryFixture.box();
            player.inventory.setItem(10, box);
            player.inventory.setItem(11, new ItemStack(Items.PISTON, 32));
            storage.newItem(new ItemStack(Items.PISTON, 32), box, 0, 10, 11);
            // Let the old delivery's idle handoff elapse before introducing inventory pressure.
            var pending = session().clearPending();
            player.containerMenu = player.inventoryMenu;
            level.tick = 2;
            assertFalse(storage.maintainOrderlyStorage());
            session().schedule(pending);
            session().beginContainerWait(40);
            storage.onContainerOpen(menu.containerId);
            player.containerMenu = menu;
            for (int slot = 0; slot < 36; slot++) {
                if (slot != 9 && slot != 10 && slot != 11 && slot != 12 && slot != 13) {
                    player.inventory.setItem(slot, new ItemStack(Items.STONE, 64));
                }
            }
            session().updatePressureRecovery(2, 4, 8);
            level.tick = 100;
        }
    }
}
//#endif
