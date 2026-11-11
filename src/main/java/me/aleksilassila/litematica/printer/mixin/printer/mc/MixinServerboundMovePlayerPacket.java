package me.aleksilassila.litematica.printer.mixin.printer.mc;

import me.aleksilassila.litematica.printer.printer.PlayerLook;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

// Priority rationale: a short-lived printer look lease must be the final packet rotation while
// leaving every packet byte-identical when no scoped/action look override exists.
@Mixin(value = ServerboundMovePlayerPacket.class, priority = 1010)
public class MixinServerboundMovePlayerPacket {
    @ModifyVariable(method = "<init>(DDDFFZZZZ)V", at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private static float modifyLookYaw(float yaw) {
        if (NetworkUtils.shouldBypassQueuedLookOverride()) {
            return yaw;
        }
        PlayerLook scopedLook = NetworkUtils.getScopedLookOverride();
        if (scopedLook != null) {
            return scopedLook.yaw;
        }
        PlayerLook playerLook = RuntimeAccess.get().actionBroker().getLook();
        if (playerLook != null) {
            return playerLook.yaw;
        }
        return yaw;
    }

    @ModifyVariable(method = "<init>(DDDFFZZZZ)V", at = @At("HEAD"), ordinal = 1, argsOnly = true)
    private static float modifyLookPitch(float pitch) {
        if (NetworkUtils.shouldBypassQueuedLookOverride()) {
            return pitch;
        }
        PlayerLook scopedLook = NetworkUtils.getScopedLookOverride();
        if (scopedLook != null) {
            return scopedLook.pitch;
        }
        PlayerLook playerLook = RuntimeAccess.get().actionBroker().getLook();
        if (playerLook != null) {
            return playerLook.pitch;
        }
        return pitch;
    }
}
