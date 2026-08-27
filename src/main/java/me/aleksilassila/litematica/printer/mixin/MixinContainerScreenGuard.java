package me.aleksilassila.litematica.printer.mixin;

import me.aleksilassila.litematica.printer.utils.mods.QuickShulkerBridge;
import me.aleksilassila.litematica.printer.utils.mods.ChestTrackerBridge;
import net.minecraft.client.Minecraft;
//#if MC > 260100
//$$ import net.minecraft.client.gui.Gui;
//#endif
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//#if MC > 260100
//$$ @Mixin(Gui.class)
//#else
@Mixin(Minecraft.class)
//#endif
public abstract class MixinContainerScreenGuard {

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void suppressAutomatedQuickShulkerScreen(@Nullable Screen screen, CallbackInfo ci) {
        if (screen instanceof AbstractContainerScreen<?>
                && QuickShulkerBridge.shouldSuppressContainerScreen()) {
            ci.cancel();
        }
        if (screen instanceof AbstractContainerScreen<?>
                && ChestTrackerBridge.shouldSuppressContainerScreen()) {
            ci.cancel();
        }
    }
}
