package me.aleksilassila.litematica.printer.mixin.printer.mc;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import me.aleksilassila.litematica.printer.interaction.HeldToolMiningProgress;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Slice;

@Mixin(Player.class)
abstract class MixinPlayerMiningEfficiency {
    @ModifyExpressionValue(method = "getDestroySpeed", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getAttributeValue(Lnet/minecraft/core/Holder;)D",
            ordinal = 0), slice = @Slice(from = @At(value = "FIELD",
            target = "Lnet/minecraft/world/entity/ai/attributes/Attributes;MINING_EFFICIENCY:Lnet/minecraft/core/Holder;")))
    private double printer$heldMiningEfficiency(double vanillaValue) {
        return HeldToolMiningProgress.efficiency((Player) (Object) this, vanillaValue);
    }
}
