package me.aleksilassila.litematica.printer.mixin.printer.chesttracker;

//#if MC >= 12104
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import red.jackf.chesttracker.impl.gui.widget.ItemListWidget;

import java.util.List;

@Mixin(value = ItemListWidget.class, remap = false)
public interface ItemListWidgetAccessor {
    @Accessor("gridWidth") int gridWidth();
    @Invoker("getOffsetItems") List<ItemStack> invokeGetOffsetItems();
}
//#endif
