package me.aleksilassila.litematica.printer.mixin.printer.chesttracker;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.utils.mods.ChestTrackerBridge;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(AbstractWidget.class)
public abstract class ItemListWidgetMixin {
    private static final int GRID_SLOT_SIZE = 18;

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void printer$mouseClicked(MouseButtonEvent event, boolean isDoubleClick,
                                      CallbackInfoReturnable<Boolean> cir) {
        if (event.button() != 1) return;
        this.printer$tryTake(event.x(), event.y(), cir);
    }

    private void printer$tryTake(double mouseX, double mouseY, CallbackInfoReturnable<Boolean> cir) {
        if (!Configs.Special.REMOTE_TAKE.getBooleanValue()
                || !((Object) this instanceof ItemListWidgetAccessor accessor)) return;
        List<ItemStack> items = accessor.invokeGetOffsetItems();
        if (items.isEmpty()) return;
        int x = (int) ((mouseX - ((AbstractWidget) (Object) this).getX()) / GRID_SLOT_SIZE);
        int y = (int) ((mouseY - ((AbstractWidget) (Object) this).getY()) / GRID_SLOT_SIZE);
        if (x < 0 || y < 0) return;
        int index = y * accessor.gridWidth() + x;
        if (index < 0 || index >= items.size()) return;
        if (ChestTrackerBridge.takeFromScreen(items.get(index))) cir.setReturnValue(true);
    }
}
