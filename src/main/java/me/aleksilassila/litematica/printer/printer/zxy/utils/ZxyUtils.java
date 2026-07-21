package me.aleksilassila.litematica.printer.printer.zxy.utils;

import me.aleksilassila.litematica.printer.printer.zxy.inventory.SwitchItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

public class ZxyUtils {
    private static final Minecraft client = Minecraft.getInstance();

<<<<<<< HEAD
    public static int switchPlayerInvToHotbarAir(int slot) {
        if (client.player == null) return -1;
=======
    public static void switchPlayerInvToHotbarAir(int slot) {
        if (client.player == null) return;
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
        LocalPlayer player = client.player;
        AbstractContainerMenu sc = player.containerMenu;
        NonNullList<Slot> slots = sc.slots;
        int i = sc.equals(player.inventoryMenu) ? 9 : 0;
<<<<<<< HEAD
        int playerSlotOrdinal = 0;
        for (; i < slots.size(); i++) {
            if (!(slots.get(i).container instanceof Inventory)) {
                continue;
            }
            if (slots.get(i).getItem().isEmpty()) {
                fi.dy.masa.malilib.util.InventoryUtils.swapSlots(sc, i, slot);
                return playerSlotOrdinal < 27 ? playerSlotOrdinal + 9 : playerSlotOrdinal - 27;
            }
            playerSlotOrdinal++;
        }
        return -1;
=======
        for (; i < slots.size(); i++) {
            if (slots.get(i).getItem().isEmpty() && slots.get(i).container instanceof Inventory) {
                fi.dy.masa.malilib.util.InventoryUtils.swapSlots(sc, i, slot);
                return;
            }
        }
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
    }

    public static void exitGameReSet() {
        SwitchItem.reSet();
    }
}
