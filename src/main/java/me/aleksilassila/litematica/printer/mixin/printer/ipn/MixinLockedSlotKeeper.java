package me.aleksilassila.litematica.printer.mixin.printer.ipn;

import me.aleksilassila.litematica.printer.integration.inventory.InventoryAutomationGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "org.anti_ad.mc.ipnext.event.LockedSlotKeeper", remap = false)
public abstract class MixinLockedSlotKeeper {
    @Shadow private static boolean screenOpening;

    @Inject(method = "onTickInGame()V", at = @At("HEAD"), cancellable = true, require = 1)
    private void litematica_printer$pauseLockedSlotKeeper(CallbackInfo ci) {
        if (InventoryAutomationGuard.shouldPauseAutoRefill()) {
            // Keep ordinary pickups pending during hand use; rebuild only after inventory moves.
            if (InventoryAutomationGuard.shouldResetAfterPause()) screenOpening = true;
            ci.cancel();
        }
    }
}
