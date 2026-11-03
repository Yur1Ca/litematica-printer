package me.aleksilassila.litematica.printer.mixin.printer.litematica;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fi.dy.masa.litematica.event.InputHandler;
import me.aleksilassila.litematica.printer.integration.inventory.PickBlockRequestScope;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = InputHandler.class, remap = false)
public class MixinSchematicPickUseKey {
    // Litematica also allows PICK_BLOCK_LAST to be explicitly bound to the use key.
    @WrapOperation(method = "handleUseKey", at = @At(value = "INVOKE",
            target = "Lfi/dy/masa/litematica/util/WorldUtils;doSchematicWorldPickBlock"))
    private boolean litematicaPrinter$userPick(boolean closest, Minecraft client, Operation<Boolean> original) {
        return PickBlockRequestScope.withUserPick(() -> original.call(closest, client));
    }
}
