package me.aleksilassila.litematica.printer.utils;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

public final class PotionStackUtils {
    private PotionStackUtils() {
    }

    public static ItemStack createWaterPotionStack() {
        return PotionContents.createItemStack(Items.POTION, Potions.WATER);
    }

    public static boolean isWaterPotion(ItemStack stack) {
        if (stack == null || !stack.is(Items.POTION)) {
            return false;
        }
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        return contents != null && contents.is(Potions.WATER);
    }
}
