package me.aleksilassila.litematica.printer.mixin.printer.litematica;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.aleksilassila.litematica.printer.integration.inventory.PickBlockRequestScope;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "fi.dy.masa.litematica.event.KeyCallbacks$KeyCallbackHotkeys", remap = false)
public class MixinSchematicPickHotkeys {
    @WrapOperation(method = "onKeyAction", at = @At(value = "INVOKE",
            target = "Lfi/dy/masa/litematica/util/WorldUtils;doSchematicWorldPickBlock"), require = 2)
    private boolean litematicaPrinter$userPick(boolean closest, Minecraft client, Operation<Boolean> original) {
        return PickBlockRequestScope.withUserPick(() -> original.call(closest, client));
    }
}
