//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The critical batch's final packet must force an authoritative snapshot without moving inventory. */
class InventorySyncPacketsTest {
    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    private static QuickShulkerPacketContractTest.AuditPlayer player() throws Exception {
        var player = QuickShulkerPacketContractTest.allocate(QuickShulkerPacketContractTest.AuditPlayer.class);
        player.inventoryForAudit = new Inventory(player, new EntityEquipment());
        var menu = new InventoryMenu(player.inventoryForAudit, true, player);
        var field = Player.class.getDeclaredField("inventoryMenu");
        field.setAccessible(true);
        field.set(player, menu);
        player.containerMenu = menu;
        return player;
    }

    private static ServerboundContainerClickPacket request(Player player) throws Exception {
        Class<?> helper = assertDoesNotThrow(() -> Class.forName(
                "me.aleksilassila.litematica.printer.utils.minecraft.InventorySyncPackets"),
                "Critical placement needs an independent, non-mutating inventory snapshot packet");
        return (ServerboundContainerClickPacket) helper.getMethod("createPlayerInventoryFullSync", Player.class)
                .invoke(null, player);
    }

    @Test void emptyCursorProducesAFullSyncNoOpForTheCurrentPlayerInventory() throws Exception {
        var player = player();
        player.inventoryForAudit.setItem(40, new ItemStack(Items.PISTON, 2));

        var packet = request(player);

        assertNotNull(packet);
        assertEquals(0, packet.containerId());
        assertEquals(-1, packet.stateId(), "A mismatching revision forces the server's complete snapshot");
        assertEquals(-1, packet.slotNum(), "Slot -999 would drop the cursor; -1 is a vanilla no-op");
        assertEquals(0, packet.buttonNum());
        assertEquals(ContainerInput.PICKUP, packet.containerInput());
        assertEquals(HashedStack.EMPTY, packet.carriedItem());
        assertTrue(packet.changedSlots().isEmpty(), "The snapshot request must not claim predicted slot contents");
        player.inventoryMenu.clicked(packet.slotNum(), packet.buttonNum(), packet.containerInput(), player);
        assertTrue(player.inventoryMenu.getCarried().isEmpty());
        assertEquals(2, player.inventoryForAudit.getItem(40).getCount());
        assertTrue(player.inventoryForAudit.getItem(40).is(Items.PISTON));
    }

    @Test void aCarriedStackRejectsTheSnapshotPacketWithoutChangingTheCursor() throws Exception {
        var player = player();
        player.inventoryMenu.setCarried(new ItemStack(Items.STONE, 3));

        assertNull(request(player));

        assertTrue(player.inventoryMenu.getCarried().is(Items.STONE));
        assertEquals(3, player.inventoryMenu.getCarried().getCount());
    }

    @Test void aForeignContainerCannotReceiveAPlayerInventorySnapshotRequest() throws Exception {
        var player = player();
        var chest = ChestMenu.threeRows(7, player.inventoryForAudit);
        player.containerMenu = chest;

        assertNull(request(player));
        assertSame(chest, player.containerMenu);
        assertNull(request(null));
    }
}
//#endif
