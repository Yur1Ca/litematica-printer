package me.aleksilassila.litematica.printer.mixin.printer.ipn;

import me.aleksilassila.litematica.printer.integration.inventory.InventoryAutomationGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = {"org.anti_ad.mc.ipnext.event.autorefill.AutoRefillHandler",
        "org.anti_ad.mc.ipnext.event.AutoRefillHandler"}, remap = false)
public abstract class MixinAutoRefillHandler {
    @Shadow public abstract void setScreenOpening(boolean opening);

    @Inject(method = "onTickInGame()V", at = @At("HEAD"), cancellable = true, require = 1)
    private void litematica_printer$pauseAutoRefill(CallbackInfo ci) {
        if (InventoryAutomationGuard.shouldPauseAutoRefill()) {
            // Inventory moves invalidate the old hand. Hand use alone must retain pending refill.
            if (InventoryAutomationGuard.shouldResetAfterPause()) this.setScreenOpening(true);
            ci.cancel();
        }
    }
}
