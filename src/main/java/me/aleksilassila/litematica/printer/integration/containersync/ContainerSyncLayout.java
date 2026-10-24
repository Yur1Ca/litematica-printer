package me.aleksilassila.litematica.printer.integration.containersync;

import java.util.List;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/** The editable storage prefix and player slots; derived recipe previews are never inventory. */
enum ContainerSyncLayout {
    CHEST(27, MenuType.GENERIC_9x3, false),
    DOUBLE_CHEST(54, MenuType.GENERIC_9x6, false),
    SHULKER(27, MenuType.SHULKER_BOX, false),
    HOPPER(5, MenuType.HOPPER, false),
    DISPENSER(9, MenuType.GENERIC_3x3, false),
    //#if MC >= 12004
    CRAFTER(9, MenuType.CRAFTER_3x3, true),
    //#endif
    ;

    final int size;
    final MenuType<?> type;
    final boolean crafter;
    ContainerSyncLayout(int size, MenuType<?> type, boolean crafter) {
        this.size = size; this.type = type; this.crafter = crafter;
    }

    static ContainerSyncLayout of(BlockState state) {
        int size = state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE ? 54 : 27;
        return of(state.getBlock(), size);
    }

    static ContainerSyncLayout of(Block block, int size) {
        if (block instanceof ChestBlock) return size == 54 ? DOUBLE_CHEST : CHEST;
        if (block instanceof BarrelBlock) return CHEST;
        if (block instanceof ShulkerBoxBlock) return SHULKER;
        if (block instanceof HopperBlock) return HOPPER;
        // DropperBlock extends DispenserBlock; the controller still matches the exact selected block.
        if (block instanceof DispenserBlock) return DISPENSER;
        //#if MC >= 12004
        if (block instanceof CrafterBlock) return CRAFTER;
        //#endif
        return null;
    }

    List<ItemStack> contents(List<ItemStack> items) {
        return items.size() == size + 36 + (crafter ? 1 : 0) ? items.subList(0, size + 36) : null;
    }

    boolean acceptsSlots(AbstractContainerMenu menu, Inventory inventory) {
        if (menu.slots.size() != size + 36 + (crafter ? 1 : 0)) return false;
        for (int i = 0; i < size + 36; i++) {
            if ((menu.getSlot(i).container == inventory) != (i >= size)) return false;
        }
        //#if MC >= 12004
        if (crafter) return menu instanceof net.minecraft.world.inventory.CrafterMenu
                && menu.getSlot(size + 36) instanceof net.minecraft.world.inventory.NonInteractiveResultSlot;
        //#endif
        return !crafter;
    }

    int disabledSlots(AbstractContainerMenu menu) {
        int mask = 0;
        //#if MC >= 12004
        if (crafter && menu instanceof net.minecraft.world.inventory.CrafterMenu crafterMenu) {
            for (int slot = 0; slot < size; slot++) if (crafterMenu.isSlotDisabled(slot)) mask |= 1 << slot;
        }
        //#endif
        return mask;
    }
}
