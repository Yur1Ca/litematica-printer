//#if MC >= 12004
package me.aleksilassila.litematica.printer.mixin;

import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import net.minecraft.client.gui.screens.inventory.CrafterScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CrafterScreen.class)
public class MixinCrafterScreen {
    @Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
    private void printer$guardCrafterInput(CallbackInfo ci) {
        // CrafterScreen predicts disabled slots before the ordinary container-click guard runs.
        if (RuntimeAccess.get().containerSync().guardManualInputBeforePrediction()) ci.cancel();
    }
}
//#endif
