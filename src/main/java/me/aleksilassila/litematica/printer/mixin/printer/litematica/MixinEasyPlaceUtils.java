//#if MC > 12100
package me.aleksilassila.litematica.printer.mixin.printer.litematica;

import com.llamalad7.mixinextras.sugar.Local;
import fi.dy.masa.litematica.util.EasyPlaceUtils;
import me.aleksilassila.litematica.printer.utils.mods.QuickShulkerBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The rewritten Easy Place path does not call WorldUtils.doEasyPlaceAction. */
@Mixin(value = EasyPlaceUtils.class, remap = false)
public class MixinEasyPlaceUtils {
    @Inject(method = "handleEasyPlace", at = @At(value = "INVOKE",
            //#if MC >= 12109
            target = "Lfi/dy/masa/litematica/util/InventoryUtils;schematicWorldPickBlock"),
            //#else
            //$$ target = "Lfi/dy/masa/litematica/util/PickBlockUtils;doPickBlockForStack"),
            //#endif
            cancellable = true)
    private static void litematicaPrinter$restock(CallbackInfoReturnable<InteractionResult> cir,
                                                 @Local(ordinal = 0) ItemStack requiredStack) {
        // Use Easy Place's actual target material after its eligibility checks. Pending manual
        // requests stop the entire placement before native inventory selection or interaction.
        if (QuickShulkerBridge.handleEasyPlacePickBlock(Minecraft.getInstance().player, requiredStack)) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}
//#endif
