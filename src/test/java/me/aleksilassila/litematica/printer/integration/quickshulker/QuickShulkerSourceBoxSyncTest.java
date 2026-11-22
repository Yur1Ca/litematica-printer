//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.HashedStack;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;
import static me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerPacketContractTest.*;

class QuickShulkerSourceBoxSyncTest {
    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    private record SlotReply(int index, ItemStack stack) { }

    private static final class MenuReplies extends Replies {
        final List<SlotReply> changes = new ArrayList<>();
        List<ItemStack> snapshot;

        @Override public void sendInitialData(AbstractContainerMenu menu, List<ItemStack> items, ItemStack cursor, int[] data) {
            snapshot = items.stream().map(ItemStack::copy).toList();
        }

        @Override public void sendSlotChange(AbstractContainerMenu menu, int slot, ItemStack stack) {
            changes.add(new SlotReply(slot, stack.copy()));
        }
    }

    @ParameterizedTest @CsvSource({"35,false", "8,false", "8,true"})
    void nextOpenSeesTheUpdatedBoxEvenWhenItIsBroadcastAfterTheHotbar(int boxSlot, boolean saveOnClose) throws Exception {
        try (var client = new ConfirmationAdvanceTest.Fixture(false, false, boxSlot)) {
            var player = allocate(AuditPlayer.class);
            player.levelForAudit = allocate(AuditLevel.class);
            var inventory = new Inventory(player, new EntityEquipment());
            player.inventoryForAudit = inventory;
            var box = client.player.inventory.getItem(boxSlot).copy();
            inventory.setItem(boxSlot, box);
            var inventoryMenu = new InventoryMenu(inventory, false, player);
            var inventoryReplies = new MenuReplies();
            inventoryMenu.setSynchronizer(inventoryReplies);
            SimpleContainer boxInventory = saveOnClose ? new SimpleContainer(27)
                    : new net.kyrptonaught.quickshulker.api.ItemStackInventory(box, 27);
            if (saveOnClose) boxInventory.setItem(0, new ItemStack(Items.STONE, 64));
            var server = new ShulkerBoxMenu(7, inventory, boxInventory);
            var replies = new MenuReplies();
            server.setSynchronizer(replies);
            replies.snapshot = null;

            var click = client.clicks().getFirst();
            server.suppressRemoteUpdates();
            server.clicked(click.slotNum(), click.buttonNum(), click.containerInput(), player);
            server.setRemoteCarried(HashedStack.EMPTY);
            server.resumeRemoteUpdates();
            if (click.stateId() == server.getStateId()) server.broadcastChanges();
            else server.broadcastFullState();
            assertTrue(boxInventory.getItem(0).isEmpty());
            assertEquals(!saveOnClose, fi.dy.masa.malilib.util.InventoryUtils.getStoredItems(box, 27).getFirst().isEmpty());

            if (replies.snapshot != null) {
                client.menu.initializeContents(1, replies.snapshot, ItemStack.EMPTY);
                client.content(replies.snapshot, ItemStack.EMPTY);
            }
            for (var change : replies.changes) {
                // Vanilla ignores slot updates for a menu that was already closed by a preceding reply.
                if (client.player.containerMenu == client.menu) client.slot(change.index(), change.stack());
            }
            assertTrue(client.player.closed);
            assertEquals(64, client.player.inventory.getItem(2).getCount());

            // Some plugins save the box only on close. That change is still due in inventoryMenu.
            if (saveOnClose) box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(boxInventory.getItems()));
            // ServerPlayer.doCloseContainer carries the old menu's remote slot state across the close.
            inventoryMenu.transferState(server);
            inventoryMenu.broadcastChanges();
            assertEquals(saveOnClose ? 1 : 0, inventoryReplies.changes.size(),
                    "Only a box changed after the last menu broadcast is resent on close");
            for (var change : inventoryReplies.changes) client.player.inventoryMenu.getSlot(change.index()).set(change.stack());

            var next = new QuickShulkerContainerSession();
            next.begin(client.player.inventory.getItem(boxSlot), false);
            next.opened(8, MenuType.SHULKER_BOX);
            assertTrue(next.confirm(8, server.slots.stream().map(s -> s.getItem().copy()).toList(), ItemStack.EMPTY),
                    "The next real box menu must still match the client box after extraction");
        }
    }
}
//#endif
